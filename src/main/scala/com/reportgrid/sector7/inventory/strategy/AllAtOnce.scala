package com.reportgrid.sector7.inventory.strategy

import blueeyes.persistence.mongo._
import blueeyes.json.xschema.DefaultSerialization._
import com.reportgrid.sector7.inventory._
import blueeyes.concurrent.Future
import net.lag.logging.Logger
import scalaz.{Failure, Success}
import blueeyes.json.JsonAST.JObject

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:57 AM
 */


/**
 * This class performs unthrottled upgrades for new service configs
 */
class AllAtOnce(val db : Database) extends DeploymentStrategy {
  def upgradesFor(host: HostEntry, onlyStable : Boolean, log : Logger) = {
    getServices map { currentServices =>
      val hostCurrent = host.currentVersions.map(s => (s.name, s.serial)).toMap

      log.debug(host.hostname + " has services: " + hostCurrent)
      log.debug("Pinned services: " + host.pinnedVersions)

      val upgrades = currentServices.map(_.latest(onlyStable)).flatMap {
        conf => {
          conf.foreach { c =>
            log.debug("Service %s latest is %s, host has %s".format(c.name, c.serial, hostCurrent.get(c.name).getOrElse(" none installed")))
          }

          conf match {
            case Some(upgrade) if !host.pinnedVersions.contains(upgrade.name) &&
                                  hostCurrent.get(upgrade.name).map(_ != upgrade.serial).getOrElse(true) => Some(upgrade)
            case _ => None
          }
        }
      } ::: host.pinnedVersions.flatMap {
        case ServiceId(name,serial) if hostCurrent(name) != serial =>
          currentServices.find(_.name == name).flatMap(_.configs.find(_.serial == serial))
        case _ => None
      } match {
        case Nil => log.info(host.hostname + " is up-to-date"); Nil
        case needed_upgrades => log.info(host.hostname + " needs " + needed_upgrades.map(_.id)); needed_upgrades
      }

      upgrades.foreach {
        config => {
          import InventoryManager.SERVICES_COLL
          db(updateMany(SERVICES_COLL).set("configs.$.deployingCount" inc(1)).where("configs.name" === config.name & "configs.serial" === config.serial)).deliver()
        }
      }

      upgrades
    }
  }

  def handleSuccess(service: String, serial: String, log : Logger) = {
    import InventoryManager.SERVICES_COLL

    log.info("Success reported for %s-%s".format(service,serial))

    db(selectOne().from(SERVICES_COLL).where("name" === service)).flatMap {
      serviceJson => serviceJson.map(_.deserialize[Service]) match {
        case Some(serviceObj) =>
          val filter = "configs.name" === service & "configs.serial" === serial
          db(updateMany(SERVICES_COLL).set("configs.$.deployedCount" inc(1)).where(filter)).flatMap { incResult =>
            db(updateMany(SERVICES_COLL).set("configs.$.deployingCount" inc(-1)).where(filter)).map{ result => Success("Updated")}
          }
        case None => Future.sync(Failure("Service " + service + " doesn't exist"))
      }
    }
  }

  def handleFailure(service: String, serial: String, log : Logger) = {
    import InventoryManager.SERVICES_COLL

    val filter = "configs.name" === service & "configs.serial" === serial

    // decrement the deploying count
    db(updateMany(SERVICES_COLL).set("configs.$.deployingCount" inc(-1)).where(filter)).flatMap { ignore =>
      db(selectOne().from(SERVICES_COLL).where("name" === service)).flatMap { serviceJson =>
        serviceJson.map(_.deserialize[Service]) match {
          case Some(serviceObj) => {
            val (failedConfigs,goodConfigs) = serviceObj.configs.partition(_.serial == serial)

            failedConfigs.headOption match {
              case Some(failed) => {
                // We only roll back if this config hasn't successfully deployed anywhere
                if (failed.deployedCount == 0) {
                  // Move the bad config into the rejects collection
                  log.warning("Failed deploy on %s-%s. Rejecting".format(service, serial))
                  val updatedService = serviceObj.copy(configs = goodConfigs, rejected = failedConfigs ::: serviceObj.rejected)
                  db(update(SERVICES_COLL).set(new MongoUpdateObject(updatedService.serialize --> classOf[JObject])).where("name" === service)).map { ignore => Success("Rejected failed config")}
                } else {
                  log.warning("Failed deploy, but the service has deployed elsewhere. Skipping reject")
                  Future.sync(Success("Skipped reject on already deployed service"))
                }
              }
              case None if serviceObj.rejected.exists(_.serial == serial) => Future.sync(Success("Configuration already marked as rejected"))
              case None => Future.sync(Failure("Non-existant config %s-%s".format(service,serial)))
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