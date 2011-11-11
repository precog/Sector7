package com.reportgrid.sector7.inventory

import blueeyes.persistence.mongo._
import blueeyes.json.xschema.DefaultSerialization._
import net.lag.logging.Logger
import org.joda.time.DateTime
import blueeyes.concurrent.Future
import scalaz.{Success, Failure, Validation}
import blueeyes.json.JsonAST._
import com.reportgrid.sector7.utils.TimeUtils
import strategy.DeploymentStrategy

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:43 AM
 */

object InventoryManager {
  final val HOSTS_COLL = "hosts"
  final val SERVICES_COLL = "services"
}

class InventoryManager(db : Database, controller : DeploymentStrategy, log : Logger) {
  import InventoryManager._

  def modConfig(serviceName: String, serial: String, data : JObject, log: Logger) : Future[Unit] = {
    (data \ "stable").deserialize[Option[Boolean]] match {
      case Some(stable) => {
        log.info("Setting %s-%s to stable".format(serviceName, serial))
        db(updateMany(SERVICES_COLL).set(MongoUpdateBuilder("configs.$.stable").set(stable)).where("configs.name" === serviceName & "configs.serial" === serial))
      }
      case None => Future.sync()
    }
  }

  def getConfigs(serviceName: String) : Future[Validation[String, List[ServiceConfig]]] = {
    db(selectOne().from(SERVICES_COLL).where("name" === serviceName)).map { result =>
      result match {
        case Some(service) => Success(service.deserialize[Service].configs)
        case None => Failure("No such service: " + serviceName)
      }
    }
  }

  def deleteConfig(serviceName : String, serial: String, log : Logger) : Future[Validation[String,Unit]] = {
    db(selectOne().from(SERVICES_COLL).where("name" === serviceName)).flatMap { result =>
      result match {
        case Some(service) => {
          val serviceObj = service.deserialize[Service]

          log.debug("Deleting config %s from service %s".format(serial, serviceName))

          val updated = serviceObj.copy(configs = serviceObj.configs.filter(_.serial != serial))

          log.debug("Updated service configs = " + updated.configs.map(_.id))

          db(update(SERVICES_COLL).set(new MongoUpdateObject(updated.serialize --> classOf[JObject])).where("name" === serviceName)).map { ignore =>
            Success(())
          }
        }
        case None => Future.sync(Failure("No such service: " + serviceName))
      }
    }
  }

  /**
   * New configs are applied as a delta against the current latest config. Files are replaced
   * based on the symlink attribute. If a given file resource doesn't have a symlink then
   * replacement is done based on the filename portion of the source. For example, the file
   *
   * <pre>
   *   { "source" : "s3://foo/some/path/test.sh" }
   * </pre>
   *
   * is matched as "test.sh".
   *
   * Hooks can be removed by specifying the string "remove" as the value, or replaced/added by
   * providing a new service file object:
   *
   * <pre>
   *   { ...
   *     "preinstall" : "remove",
   *     "postinstall" : { "source" : "s3://foo/test.sh", "mode" : "755" } }
   * </pre>
   */
  def applyConfigDelta(service: Service, config: JObject) : Validation[String, Service] = {
    val timestamp = TimeUtils.timestamp
    var newConfig = (service.latest(false) getOrElse ServiceConfig(service.name)).copy(serial = timestamp, stable = false, deployedCount = 0, deployingCount = 0)

    val fields = config.fields.map (f => (f.name, f.value)).toMap

    // Sanity check on the config name if it's sent
    fields.get("name") match {
      case Some(JString(newName)) if newName != service.name => return Failure("Config name must match service name")
      case Some(badName) => return Failure("Invalid name: " + badName.serialize)
      case _ => // noop
    }

    // Copy provided data over
    fields.get("tag") foreach { v => newConfig = newConfig.copy(tag = Some(v.deserialize[String]))}

    // Allow forced stability for now
    fields.get("stable") foreach { v => newConfig = newConfig.copy(stable = v.deserialize[Boolean])}

    def hookReplace(name : String, current : Option[ServiceFile]) = fields.get(name) match {
      case Some(JString("remove")) => None
      case Some(obj) => Some(obj.deserialize[ServiceFile])
      case _ => current
    }

    newConfig = newConfig.copy(preinstall = hookReplace("preinstall", newConfig.preinstall))
    newConfig = newConfig.copy(postinstall = hookReplace("postinstall", newConfig.postinstall))
    newConfig = newConfig.copy(preremove = hookReplace("preremove", newConfig.preremove))
    newConfig = newConfig.copy(postremove = hookReplace("postremove", newConfig.postremove))

    // Handle file replacements based on either symlink or source
    var currentFiles = newConfig.files.map(f => (f.canonicalName,f)).toMap

    fields.get("files") match {
      case Some(JArray(elements)) => {
        elements.map(_.deserialize[ServiceFile]).foreach { file =>
          currentFiles += Pair(file.canonicalName,file)
        }

        Success(service.copy(configs = newConfig.copy(files = currentFiles.values.toList) :: service.configs))
      }
      case Some(other) => Failure("Invalid files attribute: " + other.serialize)
      case None => {
        Success(service.copy(configs = newConfig.copy(files = currentFiles.values.toList) :: service.configs))
      }
    }

  }

  def addConfig(service: String, config: JObject) : Future[Validation[String, Service]] = {
    db(selectOne().from(SERVICES_COLL).where("name" === service.name)).flatMap {
      case Some(obj) => {
        applyConfigDelta(obj --> classOf[JObject], config) match {
          case Success(updated) =>
            db(update(SERVICES_COLL).set(new MongoUpdateObject(updated.serialize --> classOf[JObject])).where("name" === service)) map {
              u => Success(updated)
            }
          case fail => Future.sync(fail)
        }
      }
      case None => Future.sync(Failure("Service " + service + " doesn't exist"))
    }
  }

  def addService(service: String, descriptor : Service) : Future[Validation[String, Service]] = {
    db(selectOne().from(SERVICES_COLL).where("name" === service.name)).flatMap {
      case Some(obj) => Future.sync(Failure("Service " + service + " exists"))
      case None => {
        db(insert(descriptor.serialize --> classOf[JObject]).into(SERVICES_COLL)) map {
          ignore => Success(descriptor)
        }
      }
    }
  }

  def processFailure(service: String, serial: String, hostname : String,  log: Logger) =
    controller.handleFailure(service, serial, hostname, log)

  def processSuccess(service: String, serial: String, hostname : String, log : Logger) =
    controller.handleSuccess(service, serial, hostname, log)

  db(ensureUniqueIndex("unique_host").on("hostname").in(HOSTS_COLL)).deliver()
  db(ensureUniqueIndex("unique_service").on("name").in(SERVICES_COLL)).deliver()
  db(ensureUniqueIndex("unique_config").on("configs.name", "configs.serial").in(SERVICES_COLL)).deliver()

  /**
   * Check the given host in, update its records, and return a list of needed upgrades
   */
  def checkInHost(hostname: String, current: scala.List[ServiceId], onlyStable : Boolean, log : Logger) : Future[List[ServiceConfig]] =
    db(selectOne().from(HOSTS_COLL).where("hostname" === hostname)).flatMap { result =>

      val entry = (result.map(_.deserialize[HostEntry]).getOrElse {
          log.info("Creating new record for " + hostname)
          HostEntry(hostname, new DateTime(), Nil, Nil)
        }).copy(lastCheckin = new DateTime(), currentVersions = current)

      log.debug(hostname + " updated to " + entry)

      db(upsert(HOSTS_COLL).set(entry.serialize --> classOf[JObject]).where("hostname" === hostname)).flatMap {
        ignore => controller.upgradesFor(entry, onlyStable, log)
      }
    }

  def getServices = controller.getServices
}

