package com.reportgrid.sector7.inventory

import blueeyes.{BlueEyesServer, BlueEyesServiceBuilder}
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.concurrent.Future
import blueeyes.core.data.BijectionsChunkJson._
import blueeyes.core.data.BijectionsChunkFutureJson._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.persistence.mongo._
import blueeyes.core.service._
import blueeyes.core.http._
import akka.actor.Actor._

import strategy.{DeploymentStrategy, AllAtOnce}
import com.reportgrid.sector7.utils.RequestUtils
import akka.actor.ActorRef
import scalaz.{Validation, Failure, Success}

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/3/11 at 12:14 PM
 */

/**
 * A small bit of state to pass around our service
 */
case class InventoryState(manager : ActorRef, authtoken : String)

/**
 * The deployment services are responsible for handling configuration adds/mods as well
 * as host checkins and upgrades.
 */
trait DeploymentServices extends BlueEyesServiceBuilder with RequestUtils {
  val INVENTORY_CONFIG = "inventorydb"

  val inventoryService = service("deployer", "1.0") {
    logging { log =>
      healthMonitor { monitor => context =>
        startup {
          import context._

          log.info("Starting inventory service")

          val inventoryMongo = new RealMongo(config.configMap(INVENTORY_CONFIG))

          val db = inventoryMongo.database(config.configMap(INVENTORY_CONFIG).getString("database", "deployment"))

          // Allow the configuration to specify a custom behavior (DeploymentStrategy)
          val deployer = config.getString("deployer").map { name =>
            val constructor = Class.forName(name).getConstructor(classOf[Database])
            constructor.newInstance(db).asInstanceOf[DeploymentStrategy]
          } getOrElse (new AllAtOnce(db))

          val manager = actorOf(new InventoryManager(db, deployer, log)).start()

          log.info("Startup complete")

          Future.sync(InventoryState(manager,config.getString("authtoken").get))
        } ->
        request { state =>
          // These two lines are based on the TokenManager code in analytics
          implicit val jsonErrorTransform = (failure: HttpFailure, s: String) => HttpResponse(failure, content = Some(s.serialize))
          def authenticated[A](service : HttpService[A, Future[HttpResponse[JValue]]]) = new AuthRequiredService[A,HttpResponse[JValue]](state.authtoken, service, log)

          // Define a little utility method to handle the messy side of types, actors and futures
          def process[T](message : InventoryMessage[T])(handler : T => HttpResponse[JValue]) : Future[HttpResponse[JValue]]  =
            (state.manager.!!![Future[Validation[String,T]]](message)).toBlueEyes.flatMap { _.map {
              case Success(result) => handler(result)
              case Failure(error) => HttpResponse(BadRequest, content = Some(error))
            }}

          // Everything here is strict JSON
          jvalue {
            authenticated {
              path("/inventory/") {
                path ("services") {
                  get { request : HttpRequest[Future[JValue]] =>
                    log.info("Processing inventory services GET")
                    process(GetServices) { services => HttpResponse(OK, content = Some(services)) }
                  }
                } ~
                path("host/") {
                  path ("'hostname") {
                    post { req : HttpRequest[Future[JValue]] =>
                      req.content.map(_.flatMap { data =>
                        log.info("Checkin from " + req.parameters('hostname))

                        // Default to only stable configs unless explicitly requested
                        val onlyStable = (data \ "onlyStable").deserialize[Option[Boolean]].getOrElse(true)
                        val currentEntries = (data \ "current").deserialize[List[ServiceId]]

                        process(CheckInHost(req.parameters('hostname), currentEntries, onlyStable)) {
                          updates => HttpResponse[JValue](content = Some(updates))
                        }
                      }).getOrElse {
                        log.warning("Invalid/missing post data on checkin from " + req.parameters('hostname))
                        Future.sync(HttpResponse[JValue](HttpStatus(BadRequest, "Invalid/missing post data")))
                      }
                    }
                  } ~
                  get { req : HttpRequest[Future[JValue]] =>
                    process(GetHosts) {
                      results => HttpResponse(content = Some(results))
                    }
                  }
                } ~
                path("deploy/") {
                  path ("success/'service/'serial") {
                    post { req : HttpRequest[Future[JValue]] =>
                      req.content.map(_.flatMap { hostname =>
                        process(ProcessSuccessfulDeploy(req.parameters('service), req.parameters('serial), hostname.deserialize[String])) {
                          _ => HttpResponse[JValue](HttpStatus(OK))
                        }
                      }).getOrElse(Future.sync(HttpResponse[JValue](HttpStatus(BadRequest, "Missing host information"))))
                    }
                  } ~
                  path ("failure/'service/'serial") {
                    post { req : HttpRequest[Future[JValue]] =>
                      req.content.map(_.flatMap { hostname =>
                        process(ProcessFailedDeploy(req.parameters('service), req.parameters('serial), hostname.deserialize[String])) {
                          _ => HttpResponse[JValue](HttpStatus(OK))
                        }
                      }).getOrElse(Future.sync(HttpResponse[JValue](HttpStatus(BadRequest, "Missing host information"))))
                    }
                  }
                } ~
                path ("service/'name") {
                  post { req : HttpRequest[Future[JValue]] =>
                    req.content.map(_.flatMap { data =>
                      process(AddService(req.parameters('name))) { service =>
                        log.info(req.remoteHostIp + " added service " + req.parameters('name))
                        HttpResponse[JValue](OK)
                      }
                    }).getOrElse(Future.sync(HttpResponse[JValue](BadRequest)))
                  }
                } ~
                path ("config/'name") {
                  post { req : HttpRequest[Future[JValue]] =>
                    log.info("%s adding config for %s".format(req.remoteHostIp, req.parameters('name)))

                    req.content.map(_.flatMap { data =>
                      process(AddConfig(req.parameters('name), data --> classOf[JObject])) { serviceConfig =>
                        val latestSerial = serviceConfig.latest(false).map(_.serial).get
                        log.info("%s added config %s".format(req.remoteHostIp, serviceConfig.name + "-" + latestSerial))
                        HttpResponse[JValue](OK, content = Some(Map("serial" -> latestSerial).serialize))
                      }
                    }).getOrElse(Future.sync(HttpResponse[JValue](BadRequest)))
                  } ~
                  get { req : HttpRequest[Future[JValue]] =>
                    process(GetConfigs(req.parameters('name))) {
                      configs => HttpResponse(OK, content = Some(configs.serialize))
                    }
                  } ~
                  path ("/'serial") {
                    delete { req : HttpRequest[Future[JValue]] =>
                      log.info("%s requests deletion of %s-%s".format(req.remoteHostIp, req.parameters('name), req.parameters('serial)))
                      process(DeleteConfig(req.parameters('name), req.parameters('serial))) { ignore => HttpResponse[JValue](OK) }
                    } ~
                    post { req : HttpRequest[Future[JValue]] =>
                      req.content.map(_.flatMap { obj =>
                        log.info("%s is modifying %s-%s".format(req.remoteHostIp, req.parameters('name), req.parameters('serial)))
                        process(ModConfig(req.parameters('name), req.parameters('serial), obj --> classOf[JObject])) {
                          ignore => HttpResponse[JValue](OK)
                        }
                      }).getOrElse(Future.sync(HttpResponse[JValue](OK)))
                    }
                  }
                }
              }
            }
          }
        } ->
        shutdown { config =>
          log.info("Shutting down inventory service")
          Future sync ()
        }
      }
    }
  }

}

object DeploymentServer extends BlueEyesServer with DeploymentServices