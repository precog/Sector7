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
class AllAtOnce(db : Database) extends DeploymentStrategy(db) {
  def upgradesFor(host: HostEntry, onlyStable : Boolean, log : Logger) = {
    outOfDateServicesFor(host, log, onlyStable) map { upgrades =>
      // All we do for AllAtOnce is increment deploying and let 'er rip
      upgrades.map {
        config => {
          addDeploying(config.name, config.serial).deliver()
          config
        }
      }
    }
  }
}