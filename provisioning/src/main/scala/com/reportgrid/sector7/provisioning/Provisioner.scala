package com.reportgrid.sector7.provisioning

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/18/11 at 9:06 PM
 */
import org.jclouds.compute.ComputeService

import scala.collection.JavaConverters._
import com.weiglewilczek.slf4s.Logging
import org.jclouds.compute.domain.{Template, NodeMetadata}

case class BuildTemplate(name : String,  template : Template)

case class BuildRequest(name : String, template : BuildTemplate, steps : List[BuildStep])

case class BuildResult(name : String, node : NodeMetadata, failures : List[BuildStep])

case class BuildStep(name : String,  process : Provisioner.BuildStepFunction)

object Provisioner extends Logging {
  type BuildStepFunction = (String, NodeMetadata, ComputeService) => Boolean

  def provision(requests : List[BuildRequest], client : ComputeService) : List[BuildResult] = {
    // Set up clients by server type
    requests.groupBy(_.template).flatMap { case (buildTemplate,reqs) =>
      logger.info("Provisioning %d nodes using the %s template".format(reqs.size, buildTemplate.name))
      client.createNodesInGroup(buildTemplate.name, reqs.size, buildTemplate.template).asScala.zip(reqs).map { case (created,req) =>
        BuildResult(req.name,created,req.steps.dropWhile(_.process(req.name,created,client)))
      }
    }.toList
  }
}

