package com.reportgrid.sector7.inventory

import blueeyes.persistence.mongo._
import blueeyes.json.xschema.DefaultSerialization._
import net.lag.logging.Logger
import org.joda.time.DateTime
import blueeyes.concurrent.Future
import blueeyes.json.JsonAST._
import com.reportgrid.sector7.utils.TimeUtils
import strategy.DeploymentStrategy
import akka.actor.Actor

import scalaz._
import Scalaz._

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:43 AM
 */

object InventoryManager {
  final val HOSTS_COLL = "hosts"
  final val SERVICES_COLL = "services"
}

// Actor messages
sealed trait InventoryMessage[ReturnType]
case object GetServices extends InventoryMessage[List[Service]]
case class AddService(service : String) extends InventoryMessage[Service]
case class GetConfigs(service : String) extends InventoryMessage[List[ServiceConfig]]
case class ModConfig(service : String, serial : String,  data : JObject) extends InventoryMessage[Unit]
case class AddConfig(service : String,  data : JObject) extends InventoryMessage[Service]
case class DeleteConfig(service : String, serial : String) extends InventoryMessage[Unit]
case class ProcessSuccessfulDeploy(service : String, serial : String, host : String) extends InventoryMessage[String]
case class ProcessFailedDeploy(service : String, serial : String, host : String) extends InventoryMessage[String]
case class CheckInHost(host : String, current : JArray, onlyStable : Boolean) extends InventoryMessage[List[ServiceConfig]]
case object GetHosts extends InventoryMessage[List[HostEntry]]

class InventoryManager(db : Database, controller : DeploymentStrategy, log : Logger) extends Actor {
  import InventoryManager._

  // Make a shortcut for our function return types
  type ReturnsA[T] = Future[Validation[String, T]]

  db(ensureUniqueIndex("unique_host").on("hostname").in(HOSTS_COLL)).deliver()
  db(ensureUniqueIndex("unique_service").on("name").in(SERVICES_COLL)).deliver()
  db(ensureUniqueIndex("unique_config").on("configs.name", "configs.serial").in(SERVICES_COLL)).deliver()


  protected def receive = {
    case GetServices => self.reply_?(controller.getServices)
    case AddService(service) => self.reply_?(addService(service))
    case GetConfigs(service) => self.reply_?(getConfigs(service))
    case AddConfig(service,data) => self.reply_?(addConfig(service,data))
    case ModConfig(service, serial, data) => self.reply_?(modConfig(service, serial, data))
    case DeleteConfig(service,serial) => self.reply_?(deleteConfig(service,serial))
    case ProcessSuccessfulDeploy(service,serial,host) => self.reply_?(processSuccess(service,serial,host))
    case ProcessFailedDeploy(service,serial,host) => self.reply_?(processFailure(service,serial,host))
    case CheckInHost(host,current,onlyStable) => self.reply_?(checkInHost(host,current.deserialize[List[ServiceId]],onlyStable))
    case GetHosts => self.reply_?(getHosts())
  }

  private def addService(name: String) : ReturnsA[Service] = {
    db(selectOne().from(SERVICES_COLL).where("name" === name)).flatMap {
      case Some(obj) => Future.sync(Failure("Service " + name + " exists"))
      case None => {
        val newService = Service(name)
        db(insert(newService.serialize --> classOf[JObject]).into(SERVICES_COLL)) map {
          ignore => Success(newService)
        }
      }
    }
  }

  private def addConfig(service: String, config: JObject) : ReturnsA[Service] = {
    // TODO: this should use a mongo push instead of modifying the whole service
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

  private def getConfigs(serviceName: String) : ReturnsA[List[ServiceConfig]] = {
    db(selectOne().from(SERVICES_COLL).where("name" === serviceName)).map { result =>
      result match {
        case Some(service) => Success(service.deserialize[Service].configs)
        case None => Failure("No such service: " + serviceName)
      }
    }
  }

  private def modConfig(serviceName: String, serial: String, data : JObject) : ReturnsA[Unit] = {
    (data \ "stable").deserialize[Option[Boolean]] match {
      case Some(stable) => {
        log.info("Setting %s-%s to stable".format(serviceName, serial))
        db(updateMany(SERVICES_COLL).set(MongoUpdateBuilder("configs.$.stable").set(stable)).where("configs.name" === serviceName & "configs.serial" === serial)).map(_ => Success(()))
      }
      case None => Future.sync(Success(()))
    }
  }

  private def deleteConfig(serviceName : String, serial: String) : ReturnsA[Unit] = {
    // TODO: this should us a mongo pull instead of modifying the whole service
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

  def processFailure(service: String, serial: String, hostname : String) : ReturnsA[String] =
    controller.handleFailure(service, serial, hostname, log)

  def processSuccess(service: String, serial: String, hostname : String) : ReturnsA[String] =
    controller.handleSuccess(service, serial, hostname, log)

  /**
   * Check the given host in, update its records, and return a list of needed upgrades
   */
  private def checkInHost(hostname: String, current: scala.List[ServiceId], onlyStable : Boolean) : ReturnsA[List[ServiceConfig]] = {
    log.debug("Starting checkin for " + hostname)

    val latch = new java.util.concurrent.CountDownLatch(1)

    val update = db(selectOne().from(HOSTS_COLL).where("hostname" === hostname)).flatMap { result =>
      val entry = (result.map(_.deserialize[HostEntry]).getOrElse {
          log.info("Creating new record for " + hostname)
          HostEntry(hostname, new DateTime(), Nil, Nil)
        }).copy(lastCheckin = new DateTime(), currentVersions = current)

      log.debug(hostname + " updated to " + entry)

      val updateList = db(upsert(HOSTS_COLL).set(entry.serialize --> classOf[JObject]).where("hostname" === hostname)).flatMap {
        ignore => controller.upgradesFor(entry, onlyStable, log)
      }

      updateList
    }.deliverTo(_ => latch.countDown())

    latch.await()

    log.debug("Completed checkin for " + hostname + ":" + update)

    update
  }

  def getHosts() : ReturnsA[List[HostEntry]] =
    db(selectAll.from(HOSTS_COLL)).map(data => Success(data.map(_.deserialize[HostEntry]).toList))

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
  private def applyConfigDelta(service: Service, config: JObject) : Validation[String, Service] = {
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
}

