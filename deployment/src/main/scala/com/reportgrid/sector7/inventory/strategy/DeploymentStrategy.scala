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
import com.weiglewilczek.slf4s.Logger
import com.reportgrid.sector7.inventory._
import blueeyes.json.JsonAST.JObject
import scalaz.{Failure, Success, Validation}

abstract class DeploymentStrategy(db : Database) {
  import InventoryManager.SERVICES_COLL

  def upgradesFor(host : HostEntry, onlyStable : Boolean, log : Logger) : Future[Validation[String,List[ServiceConfig]]]

  def handleSuccess(service : String, serial : String, hostname : String, log : Logger) :  Future[Validation[String,String]] = {
    log.info("Success reported from %s for %s-%s".format(hostname, service,serial))

    updateConfig(service, serial, { config =>
      /* Host succeeded, so we add it to deployed if it doesn't exist already, and
         remove from deploying and failed */
      config.copy(
        deployed = (config.deployed + hostname),
        deploying = (config.deploying - hostname),
        failed = (config.failed - hostname)
      )
    }, "Success recorded")
  }

  def handleFailure(service : String, serial : String, hostname : String,  log : Logger) : Future[Validation[String,String]] = {
    log.warn("Failure on %s-%s reported by %s".format(service, serial, hostname))
    // TODO: For now we only reject a configuration if it hasn't deployed anywhere and it's failed more than once
    val condition = { config : ServiceConfig => config.deployed.size == 0 && config.failed.size > 1}
    failConfig(service, serial, condition, hostname, log)
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

          val hostIsCurrent = conf.map { config =>
            hostCurrent.get(config.name).map(_ == config.serial).getOrElse(false)
          }.getOrElse(false)

          conf match {
            case Some(upgrade) if hostIsCurrent && ! upgrade.deployed.contains(host.hostname) => {
              // The host reports it's deployed, but the service doesn't show it. We need to update
              log.warn("Updating missing deployment of %s on %s".format(upgrade.id, host.hostname))
              handleSuccess(upgrade.name, upgrade.serial, host.hostname, log)
              None
            }
            case Some(upgrade) if !host.pinnedVersions.contains(upgrade.name) && ! hostIsCurrent => Some(upgrade)
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

  protected def addDeploying(service : String, serial : String, hostname : String) : Future[Validation[String, String]] = {
    updateConfig(service, serial, { config =>
      config.copy(deploying = (config.deploying + hostname))
    }, "Deployment added")
  }

  protected def failConfig(service : String, serial : String, shouldReject : ServiceConfig => Boolean, hostname : String, log : Logger) : Future[Validation[String,String]]= {
    val filter = "configs.name" === service & "configs.serial" === serial

    // Update the deploying and failed counters
    updateConfig(service, serial, { config =>
      val newConfig = config.copy(deploying = (config.deploying - hostname), failed = (config.failed + hostname))

      if (shouldReject(newConfig)) {
        log.warn("Failed deploy on %s for %s-%s. Rejecting".format(hostname, service, serial))
        newConfig.copy(rejected = true)
      } else {
        log.warn("Failed deploy on %s for %s-%s, but reject condition is false".format(hostname, service, serial))
        newConfig
      }
    }, "Configuration failure processed")
  }

  protected def updateConfig(service : String, serial : String, configTransform : ServiceConfig => ServiceConfig, message : String) : Future[Validation[String,String]] = {
    // TODO: This seems really ugly, but it's not clear from the Mongo docs that we could do a replace on a specific array element
    db(selectOne().from(SERVICES_COLL).where("name" === service)).flatMap {
      serviceJson => serviceJson.map(_.deserialize[Service]) match {
        case Some(serviceObj) => {
          val newConfigs = serviceObj.configs.map { config =>
            if (config.serial == serial) {
              configTransform(config)
            } else {
              config
            }
          }
          db(upsert(SERVICES_COLL).set(serviceObj.copy(configs = newConfigs) --> classOf[JObject]).where("name" === service)).map {
            _ => println("Service updated"); Success(message)
          }
        }
        case None => Future.sync(Failure("Service " + service + " doesn't exist"))
      }
    }
  }
}