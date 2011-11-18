/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:43 AM
 */
package com.reportgrid.sector7.inventory.strategy

import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.mongo._

import com.reportgrid.sector7.inventory.Service._
import blueeyes.concurrent.Future
import net.lag.logging.Logger
import com.reportgrid.sector7.inventory._
import blueeyes.json.JsonAST.JObject
import scalaz.{Failure, Success, Validation}

abstract class DeploymentStrategy(db : Database) {
  import InventoryManager.SERVICES_COLL

  def upgradesFor(host : HostEntry, onlyStable : Boolean, log : Logger) : Future[Validation[String,List[ServiceConfig]]]

  def handleSuccess(service : String, serial : String, hostname : String, log : Logger) :  Future[Validation[String,String]] = {
    log.info("Success reported from %s for %s-%s".format(hostname, service,serial))

    db(selectOne().from(SERVICES_COLL).where("name" === service)).flatMap {
      serviceJson => serviceJson.map(_.deserialize[Service]) match {
        case Some(serviceObj) => addDeployed(service, serial)
        case None => Future.sync(Failure("Service " + service + " doesn't exist"))
      }
    }
  }

  def handleFailure(service : String, serial : String, hostname : String,  log : Logger) : Future[Validation[String,String]] = {
    log.warning("Failured on %s-%s reported by %s".format(service, serial, hostname))
    failConfig(service, serial, _.deployedCount == 0, hostname, log)
  }

  def getServices : Future[List[Service]] =
    this.db(select().from(SERVICES_COLL)).map {
      s => s.toList.map(_.deserialize[Service])
    }

  /**
   * Computes which services need updates based on the given host. Filters against host restrictions
   * on the service itself, as well as pinned versions on the host.
   */
  protected def outOfDateServicesFor(host: HostEntry, onlyStable: Boolean, log: Logger): Future[List[ServiceConfig]] =
    getServices.map { currentServices =>
      val hostCurrent = host.currentVersions.map(s => (s.name, s.serial)).toMap

      log.debug(host.hostname + " has services: " + hostCurrent)
      log.debug("Pinned services: " + host.pinnedVersions)

      val upgrades = currentServices.filter{ service =>
        // We can only deploy unrestricted services or services restricted to the current host
        service.onlyHosts.isEmpty || service.onlyHosts.contains(host.hostname)
      }.map(_.latest(onlyStable)).flatMap {
        conf => {
          conf.foreach {
            c =>
              log.debug("Service %s latest is %s, host has %s".format(c.name, c.serial, hostCurrent.get(c.name).getOrElse(" none installed")))
          }

          conf match {
            case Some(upgrade) if !host.pinnedVersions.contains(upgrade.name) &&
              hostCurrent.get(upgrade.name).map(_ != upgrade.serial).getOrElse(true) => Some(upgrade)
            case _ => None
          }
        }
      } ::: host.pinnedVersions.flatMap {
        case ServiceId(name, serial) if hostCurrent(name) != serial =>
          currentServices.find(_.name == name).flatMap(_.configs.find(_.serial == serial))
        case _ => None
      } match {
        case Nil => log.info(host.hostname + " is up-to-date"); Nil
        case needed_upgrades => log.info(host.hostname + " needs " + needed_upgrades.map(_.id)); needed_upgrades
      }
      upgrades
    }

  protected def addDeploying(service : String, serial : String) =
    db(updateMany(SERVICES_COLL).set("configs.$.deployingCount" inc(1)).where("configs.name" === service & "configs.serial" === serial))

  protected def addDeployed(service : String,  serial : String) : Future[Validation[String,String]] = {
    val filter = "configs.name" === service & "configs.serial" === serial
    db(updateMany(SERVICES_COLL).set("configs.$.deployedCount" inc(1)).where(filter)).flatMap { incResult =>
      db(updateMany(SERVICES_COLL).set("configs.$.deployingCount" inc(-1)).where(filter)).map{ result => Success("Updated")}
    }
  }

  protected def failConfig(service : String, serial : String, shouldReject : ServiceConfig => Boolean, hostname : String, log : Logger) : Future[Validation[String,String]]= {
    val filter = "configs.name" === service & "configs.serial" === serial

    // decrement the deploying count and increment the failed count
    db(updateMany(SERVICES_COLL).set("configs.$.deployingCount".inc(-1)).where(filter)) flatMap { _ =>
      db(updateMany(SERVICES_COLL).set("configs.$.failedCount".inc(1)).where(filter)) flatMap { _ =>
        db(selectOne().from(SERVICES_COLL).where("name" === service)).flatMap { serviceJson =>
          serviceJson.map(_.deserialize[Service]) match {
            case Some(serviceObj) => {
              val (failedConfigs,goodConfigs) = serviceObj.configs.partition(_.serial == serial)

              failedConfigs.headOption match {
                case Some(failed) => {
                  // Allow the strategy to decide whether to reject
                  if (shouldReject(failed)) {
                    // Mark the bad config as rejected
                    log.warning("Failed deploy on %s for %s-%s. Rejecting".format(hostname, service, serial))
                    val updatedService = serviceObj.copy(configs = (failed.copy(rejected = true)) :: goodConfigs)
                    db(update(SERVICES_COLL).set(updatedService.serialize --> classOf[JObject]).where("name" === service)).map { ignore => Success("Rejected failed config")}
                  } else {
                    log.warning("Failed deploy on %s for %s-%s, but reject condition is false".format(hostname, service, serial))
                    Future.sync(Success("Skipped reject on already deployed service"))
                  }
                }
                case None if serviceObj.configs.exists(config => config.serial == serial && config.rejected) => Future.sync(Success("Configuration already marked as rejected"))
                case None => {
                  log.warning("Non-existant config %s-%s reported by %s".format(service,serial, hostname))
                  Future.sync(Failure("Non-existant config %s-%s".format(service,serial)))
                }
              }
            }
            case None => {
              log.error("Invalid deploy failure registered for non-existent service " + service)
              Future.sync(Failure("Service " + service + " doesn't exist"))
            }
          }
        }
      }
    }
  }
}