package com.reportgrid.sector7.inventory

import blueeyes.{BlueEyesServer, BlueEyesServiceBuilder}
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json.xschema._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.concurrent.Future
import blueeyes.core.data.ByteChunk
import blueeyes.core.data.BijectionsChunkJson._
import blueeyes.core.data.BijectionsChunkFutureJson._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.http.{HttpStatus, HttpRequest, HttpResponse}
import blueeyes.persistence.mongo._
import blueeyes.persistence.mongo.json.BijectionsMongoJson._
import org.joda.time.Instant
import strategy.AllAtOnce
import scalaz.{Failure, Success}

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/3/11 at 12:14 PM
 */

case class InventoryState(manager : InventoryManager, authtoken : String)

trait DeploymentServices extends BlueEyesServiceBuilder {
  val INVENTORY_CONFIG = "inventorydb"

  val inventoryService = service("deployer", "1.0") {
    logging { log =>
      healthMonitor { monitor => context =>
        startup {
          import context._

          log.info("Starting inventory service")

          val inventoryMongo = new RealMongo(config.configMap(INVENTORY_CONFIG))

          val db = inventoryMongo.database(config.configMap(INVENTORY_CONFIG).getString("database", "deployment"))

          val deployer = config.getString("deployer").map(Class.forName(_).newInstance().asInstanceOf[DeploymentStrategy]) getOrElse (new AllAtOnce(db))

          val manager = new InventoryManager(db, deployer, log)

          log.info("Startup complete")

          Future.sync(InventoryState(manager,config.getString("authtoken").get))
        } ->
        request { state =>
          jvalue {
            path("/inventory/") {
              path ("services") {
                get {
                  request : HttpRequest[Future[JValue]] =>
                    log.info("Processing inventory services GET")
                    state.manager.getServices.map { services => HttpResponse(content = Some(services.serialize))}
                }
              } ~
              path("host/'hostname") {
                post {
                  AuthHandler(state.authtoken) {
                    req =>
                      req.content match {
                        case Some(x) => x.flatMap { data =>
                          log.info("Checkin from " + req.parameters('hostname))

                          // Default to only stable configs unless explicitly requested
                          val onlyStable = (data \ "onlyStable").deserialize[Option[Boolean]].getOrElse(true)
                          val currentEntries = (data \ "current").deserialize[List[ServiceId]]

                          state.manager.checkInHost(req.parameters('hostname), currentEntries, onlyStable, log).map {
                            updates => HttpResponse[JValue](content = Some(updates))
                          }
                        }
                        case _ => {
                          log.warning("Invalid/missing post data on checkin from " + req.parameters('hostname))
                          Future.sync(HttpResponse[JValue](HttpStatus(BadRequest, "Invalid/missing post data")))
                        }
                      }
                  }
                }
              } ~
              path("deploy/") {
                path ("success/'service/'serial") {
                  post {
                    AuthHandler(state.authtoken) {
                      req =>
                      state.manager.processSuccess(req.parameters('service), req.parameters('serial), log) map {
                        case Success(msg) => HttpResponse[JValue](HttpStatus(OK))
                        case Failure(message) => HttpResponse[JValue](HttpStatus(BadRequest, "Error processing success: " + message))
                      }
                    }
                  }
                } ~
                path ("failure/'service/'serial") {
                  post {
                    AuthHandler(state.authtoken) {
                      req =>
                      state.manager.processFailure(req.parameters('service), req.parameters('serial), log) map {
                        case Success(s) => HttpResponse[JValue](HttpStatus(OK))
                        case Failure(message) => HttpResponse[JValue](HttpStatus(BadRequest, "Error processing failure: " + message))
                      }
                    }
                  }
                }
              } ~
              path ("service/'name") {
                post {
                  AuthHandler(state.authtoken) {
                    req =>
                    req.content match {
                      case Some(x) => x.flatMap { data =>
                        state.manager.addService(req.parameters('name), data.deserialize[Service]) map {
                          case Success(service) => {
                            log.info(req.remoteHost.map(_.getHostAddress).getOrElse("Unknown host") + " added service " + req.parameters('name))
                            HttpResponse(OK)
                          }
                          case Failure(message) => HttpResponse(content = Some(message), status = BadRequest)
                        }
                      }
                      case None => Future.sync(HttpResponse(BadRequest))
                    }
                  }
                }
              } ~
              path ("config/'name") {
                post {
                  AuthHandler(state.authtoken) {
                    req =>
                    log.info("%s adding config for %s".format(req.remoteHost.map(_.getHostAddress).getOrElse("Unknown host"), req.parameters('name)))

                    req.content match {
                      case Some(x) => x.flatMap { data =>
                        state.manager.addConfig(req.parameters('name), data --> classOf[JObject]) map {
                          case Success(serviceConfig) => {
                            log.info("%s added config %s".format(req.remoteHost.map(_.getHostAddress).getOrElse("Unknown host"), serviceConfig.name + "-" + serviceConfig.latest(false).map(_.serial).get))
                            HttpResponse(OK)
                          }
                          case Failure(message) => HttpResponse[JValue](content = Some(message), status = BadRequest)
                        }
                      }
                      case None => Future.sync(HttpResponse(BadRequest))
                    }
                  }
                } ~
                get {
                  AuthHandler(state.authtoken) { req =>
                    state.manager.getConfigs(req.parameters('name)) map {
                      case Success(configs) => HttpResponse(OK, content = Some(configs.serialize))
                      case Failure(message) => HttpResponse[JValue](BadRequest, content = Some(message))
                    }
                  }
                } ~
                path ("/'serial") {
                  delete {
                    AuthHandler(state.authtoken) { req =>
                      log.info("%s requests deletion of %s-%s".format(req.remoteHost.map(_.getHostAddress).getOrElse("Unknown host"), req.parameters('name), req.parameters('serial)))
                      state.manager.deleteConfig(req.parameters('name), req.parameters('serial), log) map { ignore => HttpResponse[JValue](OK) }
                    }
                  } ~
                  post {
                    AuthHandler(state.authtoken) { req =>
                      req.content match {
                        case Some(data) => data.flatMap { obj =>
                          log.info("%s is modifying %s-%s".format(req.remoteHost.map(_.getHostAddress).getOrElse("Unknown host"), req.parameters('name), req.parameters('serial)))
                          state.manager.modConfig(req.parameters('name), req.parameters('serial), obj --> classOf[JObject], log).map {
                            ignore => HttpResponse(OK)
                          }
                        }
                        case None => Future.sync(HttpResponse(OK))
                      }
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