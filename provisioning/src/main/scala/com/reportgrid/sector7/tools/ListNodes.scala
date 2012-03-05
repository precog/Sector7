package com.reportgrid.sector7.tools

import scala.collection.JavaConverters._
import org.jclouds.compute.{ComputeServiceContext, ComputeServiceContextFactory}
import org.jclouds.compute.domain.ComputeMetadata
import net.lag.configgy.Configgy
import com.reportgrid.sector7.utils.JCloudsFactory

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/21/11 at 7:35 AM
 */


object ListNodes {
  def main(args: Array[String]) {
    if (args.length != 1) {
      println("Usage: ListNodes <config file>")
    }

    Configgy.configure(args(0))

    val context = JCloudsFactory.computeContextForProvider(Configgy.config)

    context.getComputeService.listNodes.asScala.foreach{ node => println(node.getName) }

    context.close()
  }
}