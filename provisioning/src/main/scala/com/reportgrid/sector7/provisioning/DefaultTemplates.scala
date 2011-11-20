/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/19/11 at 7:11 AM
 */
package com.reportgrid.sector7.provisioning

import org.jclouds.compute.ComputeService
import org.jclouds.compute.domain.{OsFamily, TemplateBuilder}
import net.lag.configgy.Config

object DefaultTemplates {
  private def templateFor(client : ComputeService, ram : Int,  cores : Int) =
    client.templateBuilder().minRam(ram).minCores(cores).
      osFamily(OsFamily.UBUNTU).osVersionMatches("10.04").os64Bit(true).build()

  def AppServer(client : ComputeService) =
    BuildTemplate("appserver", templateFor(client, 8192, 4))

  def fromConfig(client : ComputeService, config : Config, serverType : String) =
    BuildTemplate(
      serverType,
      templateFor(
        client,
        config.getInt(serverType + ".minram", 256),
        config.getInt(serverType + ".mincores", 1)
      )
    )
}