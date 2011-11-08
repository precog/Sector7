package com.reportgrid.sector7.inventory

import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.mongo._

import com.reportgrid.sector7.inventory.Service._
import scalaz.Validation
import blueeyes.concurrent.Future
import net.lag.logging.Logger

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:43 AM
 */

trait DeploymentStrategy {
  val db : Database

  def upgradesFor(host : HostEntry, onlyStable : Boolean, log : Logger) : Future[List[ServiceConfig]]

  def handleSuccess(service : String, serial : String, log : Logger) :  Future[Validation[String,String]]

  def handleFailure(service : String, serial : String, log : Logger) : Future[Validation[String,String]]

  def getServices : Future[List[Service]] =
    this.db(select().from(InventoryManager.SERVICES_COLL)).map {
      s => s.toList.map(_.deserialize[Service])
    }
}