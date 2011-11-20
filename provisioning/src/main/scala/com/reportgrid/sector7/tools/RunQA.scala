package com.reportgrid.sector7.tools

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/26/11 at 8:39 PM
 */
import net.lag.configgy.{Configgy, Config}
import com.reportgrid.sector7.utils.{ConfigValidator, Parameter, JCloudsFactory}
import com.weiglewilczek.slf4s.Logging
import com.reportgrid.sector7.provisioning._

object RunQA extends ConfigValidator with Logging {
  val parameters = List()

  def runQAEnv(config: Config) {
    val context = JCloudsFactory.contextForProvider(config)
    val client = context.getComputeService

    // Setup our template and boot
    val environment = "QA"
    val template = DefaultTemplates.AppServer(client)
    val steps = List(DefaultSteps.fixDns, DefaultSteps.chefBoot(environment, Some("role[base]")))

    // For our qa env, we want one server and several "attackers"
    val timestamp = DefaultSteps.timestamp
    val attackerHostnames = (1 to config.getInt("attackerCount", 4)).map("qa-attacker-%02d-%s.reportgrid.com".format(_, timestamp))
    val attackerSteps = List(DefaultSteps.fixDns, DefaultSteps.chefBoot(environment, Some("role[base],recipe[java]")))

    val requests = List(
      BuildRequest(
        "qaserver-%s.reportgrid.com".format(timestamp),
        DefaultTemplates.AppServer(client),
        List(
          DefaultSteps.fixDns,
          DefaultSteps.chefBoot(environment, Some("role[base],recipe[mongodb::server],role[appserverV2],role[vizserver]"))
        )
      )
    ) ++ attackerHostnames.map(BuildRequest(_,DefaultTemplates.fromConfig(client,config,"attacker"),attackerSteps))

    Provisioner.provision(requests, client).foreach { result =>
      logger.info("Setup of %s (%s) ".format(result.name, result.node.getId) + (result.failures match {
        case Nil => "succeeded"
        case failures => "failed: " + failures.map(_.name).mkString(", ")
      }))
    }

    context.close()
  }

  def main(args: Array[String]) {
    args match {
      case Array(configName) => {
        Configgy.configure(configName)

        // Verify minimum params
        JCloudsFactory.check(Configgy.config) match {
          case Nil => runQAEnv(Configgy.config)
          case missing => {
            println(missing.map(_._1).mkString("The configuration is missing the following parameters: ", ", ", ""))
            sys.exit(1)
          }
        }
      }
      case _ => {
        println("Usage: RunQA <config file>")
        println("  Parameters in the configuration are (required marked with '*'):")
        JCloudsFactory.parameters.foreach {
          case Parameter(name, req, desc) =>
            println("    %s%-20s - %s".format(if (req) "*" else " ", name, desc.getOrElse("No description")))
        }
      }
    }

  }
}