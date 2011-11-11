package com.reportgrid.sector7.inventory.strategy

import blueeyes.persistence.mongo._
import blueeyes.json.xschema.DefaultSerialization._

import net.lag.logging.Logger
import com.reportgrid.sector7.inventory._

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/10/11 at 6:48 PM
 */
class OneAtATime(db : Database) extends DeploymentStrategy(db) {
  import InventoryManager.SERVICES_COLL

  def upgradesFor(host: HostEntry, onlyStable: Boolean, log: Logger) = {
    outOfDateServicesFor(host, log, onlyStable).map { upgrades =>
      // For each candidate, check to see if someone is already deploying. If so, we skip on this round
      upgrades.flatMap { config =>
        if (config.deployingCount > 0) {
          log.info("Skipping upgrade on %s-%s. Already deploying".format(config.name, config.serial))
          None
        } else {
          log.info("Upgrading %s to %s-%s".format(host.hostname, config.name, config.serial))
          addDeploying(config.name, config.serial)
          Some(config)
        }
      }
    }
  }
}