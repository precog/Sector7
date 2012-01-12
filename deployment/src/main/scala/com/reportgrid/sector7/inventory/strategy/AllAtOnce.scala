/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:57 AM
 */
package com.reportgrid.sector7.inventory.strategy

import blueeyes.persistence.mongo._
import com.reportgrid.sector7.inventory._
import blueeyes.concurrent.Future
import com.weiglewilczek.slf4s.Logger
import scalaz.{Validation, Success}

/**
 * This class performs unthrottled upgrades for new service configs
 */
class AllAtOnce(db : Database) extends DeploymentStrategy(db) {
  def upgradesFor(host: HostEntry, onlyStable : Boolean, log : Logger) : Future[Validation[String, List[ServiceConfig]]] = {
    outOfDateServicesFor(host, onlyStable, log).map { upgrades =>
      // All we do for AllAtOnce is increment deploying and let 'er rip
      Success(upgrades.map {
        config => {
          addDeploying(config.name, config.serial, host.hostname)
          config
        }
      })
    }
  }
}