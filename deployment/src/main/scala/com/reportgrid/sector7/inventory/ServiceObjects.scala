/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/3/11 at 9:17 PM
 */
package com.reportgrid.sector7.inventory

import org.joda.time.DateTime

import scalaz._
import scalaz.Scalaz._

import blueeyes.json.xschema._
import blueeyes.json.JsonAST._
import blueeyes.json.xschema.{ ValidatedExtraction, Extractor, Decomposer }
import blueeyes.json.xschema.Extractor._
import blueeyes.json.xschema.DefaultSerialization._
import com.reportgrid.sector7.utils.TimeUtils

trait SerializationHelpers {
  def fieldHasValue(field: JField): Boolean = field.value match {
    case JNull => false
    case _ => true
  }
}

case class ServiceFile(source : String,
                       symlink : Option[String],
                       mode : Option[String]) {
  def canonicalName = symlink.getOrElse(source.split('/').last)
}

trait ServiceFileSerialization extends SerializationHelpers {
  implicit val ServiceFileDecomposer : Decomposer[ServiceFile] = new Decomposer[ServiceFile] {
    def decompose(file : ServiceFile) : JValue = JObject(
      List(
        JField("source", file.source.serialize),
        JField("symlink", file.symlink.serialize),
        JField("mode", file.mode.serialize)
      ).filter(fieldHasValue)
    )
  }

  implicit val ServiceFileExtractor : Extractor[ServiceFile] = new Extractor[ServiceFile] with ValidatedExtraction[ServiceFile] {
    override def validated(obj: JValue) : Validation[Error,ServiceFile] = (
        (obj \ "source").validated[String] |@|
        (obj \ "symlink").validated[Option[String]] |@|
        (obj \ "mode").validated[Option[String]]).apply(ServiceFile(_,_,_))
  }
}

object ServiceFile extends ServiceFileSerialization

case class ServiceConfig(name : String,
                         tag : Option[String] = None,
                         serial : String = TimeUtils.timestamp,
                         stable : Boolean = false,
                         rejected : Boolean = false,
                         deployed : Set[String] = Set(), // A set of hosts where this has deployed
                         deploying : Set[String] = Set(), // A set of hosts where this is currently deploying
                         failed : Set[String] = Set(), // A set of hosts where deployment failed
                         preinstall : Option[ServiceFile] = None,
                         postinstall : Option[ServiceFile] = None,
                         preremove : Option[ServiceFile] = None,
                         postremove : Option[ServiceFile] = None,
                         files : List[ServiceFile] = Nil) {
  def id = name + "-" + serial
}

trait ServiceConfigSerialization extends SerializationHelpers {
  implicit val  ServiceConfigDecomposer : Decomposer[ServiceConfig] = new Decomposer[ServiceConfig] {
    def decompose(config: ServiceConfig) : JValue = JObject(
      List(
        JField("name", config.name.serialize),
        JField("tag", config.tag.serialize),
        JField("serial", config.serial.serialize),
        JField("stable", config.stable.serialize),
        JField("rejected", config.rejected.serialize),
        JField("deployed", config.deployed.serialize),
        JField("deploying", config.deploying.serialize),
        JField("failed", config.failed.serialize),
        JField("preinstall", config.preinstall.serialize),
        JField("postinstall", config.postinstall.serialize),
        JField("preremove", config.preremove.serialize),
        JField("postremove", config.postremove.serialize),
        JField("files", config.files.serialize)
      ).filter(fieldHasValue)
    )
  }

  implicit val  ServiceConfigExtractor : Extractor[ServiceConfig] = new Extractor[ServiceConfig] with ValidatedExtraction[ServiceConfig] {
    override def validated(obj: JValue) : Validation[Error,ServiceConfig] =
      (obj \ "files").validated[List[ServiceFile]].flatMap { files =>
        ((obj \ "name").validated[String] |@|
         (obj \ "tag").validated[Option[String]] |@|
         (obj \ "serial").validated[String] |@|
         (obj \ "stable").validated[Boolean] |@|
         (obj \? "rejected").getOrElse(JBool(false)).validated[Boolean] |@|
         (obj \? "deployed").getOrElse(JArray.empty).validated[Set[String]] |@|
         (obj \? "deploying").getOrElse(JArray.empty).validated[Set[String]] |@|
         (obj \? "failed").getOrElse(JArray.empty).validated[Set[String]] |@|
         (obj \ "preinstall").validated[Option[ServiceFile]] |@|
         (obj \ "postinstall").validated[Option[ServiceFile]] |@|
         (obj \ "preremove").validated[Option[ServiceFile]] |@|
         (obj \ "postremove").validated[Option[ServiceFile]]
        ).apply(ServiceConfig(_,_,_,_,_,_,_,_,_,_,_,_,files))
      }
  }
}

object ServiceConfig extends ServiceConfigSerialization

case class Service(name : String, configs : List[ServiceConfig] = Nil, onlyHosts : List[String] = Nil) {
  def latest(onlyStable : Boolean) = {
    val current = configs.sortBy(_.serial).filter(!_.rejected)

    (if (onlyStable) {
      current.filter(_.stable)
    } else {
      current
    }).lastOption
  }
}

trait ServiceSerialization extends SerializationHelpers {
  implicit val  ServiceDecomposer : Decomposer[Service] = new Decomposer[Service] {
    def decompose(service: Service) : JValue = JObject(
      List(
        JField("name", service.name.serialize),
        JField("configs", service.configs.serialize),
        JField("onlyHosts", service.onlyHosts.serialize)
      ).filter(fieldHasValue)
    )
  }

  implicit val  ServiceExtractor : Extractor[Service] = new Extractor[Service] with ValidatedExtraction[Service] {
    override def validated(obj: JValue) : Validation[Error,Service] = {
      ((obj \ "name").validated[String] |@|
       (obj \? "configs").getOrElse(JArray.empty).validated[List[ServiceConfig]] |@|
       (obj \? "onlyHosts").getOrElse(JArray.empty).validated[List[String]]).apply(Service(_,_,_))
    }
  }
}

object Service extends ServiceSerialization

case class ServiceId(name : String, serial : String)

trait ServiceIdSerialization extends SerializationHelpers {
  implicit val  ServiceIdDecomposer : Decomposer[ServiceId] = new Decomposer[ServiceId] {
    def decompose(serviceId: ServiceId) : JValue = JObject(
      List(
        JField("name", serviceId.name.serialize),
        JField("serial", serviceId.serial.serialize)
      ).filter(fieldHasValue)
    )
  }

  implicit val  ServiceIdExtractor : Extractor[ServiceId] = new Extractor[ServiceId] with ValidatedExtraction[ServiceId] {
    override def validated(obj: JValue) : Validation[Error,ServiceId] = (
        (obj \ "name").validated[String] |@|
        (obj \ "serial").validated[String]).apply(ServiceId(_,_))
  }
}

object ServiceId extends ServiceIdSerialization

case class HostEntry(hostname : String,
                     lastCheckin : DateTime,
                     currentVersions: List[ServiceId],
                     pinnedVersions : List[ServiceId])

trait HostEntrySerialization extends SerializationHelpers {
  implicit val  HostEntryDecomposer : Decomposer[HostEntry] = new Decomposer[HostEntry] {
    def decompose(host: HostEntry) : JValue = JObject(
      List(
        JField("hostname", host.hostname.serialize),
        JField("lastCheckin", host.lastCheckin.serialize),
        JField("currentVersions", host.currentVersions.serialize),
        JField("pinnedVersions", host.pinnedVersions.serialize)
      ).filter(fieldHasValue)
    )
  }

  implicit val  HostEntryExtractor : Extractor[HostEntry] = new Extractor[HostEntry] with ValidatedExtraction[HostEntry] {
    override def validated(obj: JValue) : Validation[Error, HostEntry] = (
        (obj \ "hostname").validated[String] |@|
        (obj \ "lastCheckin").validated[DateTime] |@|
        (obj \ "currentVersions").validated[List[ServiceId]] |@|
        (obj \ "pinnedVersions").validated[List[ServiceId]]).apply(HostEntry(_,_,_,_))
  }
}

object HostEntry extends HostEntrySerialization




