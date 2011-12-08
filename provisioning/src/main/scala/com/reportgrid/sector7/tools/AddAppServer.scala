/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/26/11 at 8:39 PM
 */
package com.reportgrid.sector7.tools


import net.lag.configgy.{Configgy, Config}
import com.reportgrid.sector7.utils.{ConfigValidator, Parameter, JCloudsFactory}
import com.weiglewilczek.slf4s.Logging
import com.reportgrid.sector7.provisioning._

object AddAppServer extends ConfigValidator with Logging {
  val parameters = List()

  def provisionServer(serverType : String, fqdns: Array[String], env : String, config: Config) {
    val context = JCloudsFactory.contextForProvider(config)
    val client = context.getComputeService

    // Setup our template and boot
    val template = DefaultTemplates.AppServer(client)
    val steps = List(DefaultSteps.fixDns, DefaultSteps.chefBoot(env, config.getString(serverType + ".roles") orElse Some("role[base]")))

    Provisioner.provision(fqdns.map(BuildRequest(_, template, steps)).toList, client).foreach { result =>
      logger.info("Setup of %s (%s) ".format(result.name, result.node.getId) + (result.failures match {
        case Nil => "succeeded"
        case failures => "failed: " + failures.map(_.name).mkString(", ")
      }))
    }

    context.close()
  }

  def main(args: Array[String]) {
    args match {
      case Array(configName, fqdn, environment, serverType) => {
        Configgy.configure(configName)

        // Verify minimum params
        JCloudsFactory.check(Configgy.config) match {
          case Nil => provisionServer(serverType, fqdn.split(','), environment, Configgy.config)
          case missing => {
            println(missing.map(_._1).mkString("The configuration is missing the following parameters: ", ", ", ""))
            sys.exit(1)
          }
        }
      }
      case _ => {
        println("Usage: AddAppServer <config file> <fqdns, comma-separated> <environment> <server type>")
        println("  Parameters in the configuration are (required marked with '*'):")
        JCloudsFactory.parameters.foreach {
          case Parameter(name, req, desc) =>
            println("    %s%-20s - %s".format(if (req) "*" else " ", name, desc.getOrElse("No description")))
        }
      }
    }

  }
}