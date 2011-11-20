/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/10/11 at 6:48 PM
 */
package com.reportgrid.sector7.inventory.strategy

import blueeyes.persistence.mongo._

import net.lag.logging.Logger
import com.reportgrid.sector7.inventory._
import scalaz.{Success, Validation}
import blueeyes.concurrent.Future

/**
 * This class performs upgrades only one host at a time
 */
class OneAtATime(db : Database) extends DeploymentStrategy(db) {
  type Result = Future[Validation[String, List[ServiceConfig]]]
  def upgradesFor(host: HostEntry, onlyStable: Boolean, log: Logger) : Result =
    outOfDateServicesFor(host, onlyStable, log).map { upgrades =>
      // For each candidate, check to see if someone else is already deploying. If so, we skip on this round
      Success(upgrades.flatMap { config =>
        if (config.deploying.size > 0 && ! config.deploying.contains(host.hostname)) {
          log.info("Skipping upgrade on %s-%s. Already deploying to %s".format(config.name, config.serial, config.deploying.mkString(", ")))
          None
        } else {
          log.info("Upgrading %s to %s-%s".format(host.hostname, config.name, config.serial))
          addDeploying(config.name, config.serial, host.hostname)
          Some(config) // TODO: Technically we probably want to return the updated config
        }
      })
    }
}