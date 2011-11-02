package com.reportgrid.sector7.tools

import scala.collection.JavaConverters._
import sys.process._

import net.lag.configgy.{Configgy, Config}
import org.jclouds.compute.domain.OsFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.io.{FileWriter, File}
import com.reportgrid.sector7.utils.{FileUtils, ConfigValidator, Parameter, JCloudsFactory}
import com.weiglewilczek.slf4s.Logging

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/26/11 at 8:39 PM
 */


object AddAppServer extends ConfigValidator with Logging {
  val parameters = List()

  def provisionServer(serverType : String, fqdns: Array[String], env : String, config: Config) {
    val timestamp = (new SimpleDateFormat("yyyyMMddHHmmss")).format(new Date)

    val context = JCloudsFactory.contextForProvider(config)
    val client = context.getComputeService

    // Setup our template
    // TODO: Make this not hardcoded
    val template = client.templateBuilder()

    // Set up according to the config
    template.minRam(config.getInt(serverType + ".minram", 8192))
    template.minCores(config.getInt(serverType + ".mincores", 4))

    template.osFamily(OsFamily.UBUNTU).osVersionMatches("10.04").os64Bit(true)

    val newNodes = client.createNodesInGroup(serverType, fqdns.size, template.build()).asScala.map {
      // TODO: handle the possibility of no public IPs
      n => (n.getId, n.getPublicAddresses.iterator().next(), n.getCredentials.identity, n.getCredentials.credential)
    }

    newNodes.zip(fqdns).foreach {
      case ((nodeId, mainPublicIP, identity, credential),fqdn) =>
        val hostname = fqdn.split('.').head

        // Fix DNS prior to chef run
        val script =
          "mv /etc/hostname /etc/hostname." + timestamp + " && echo " + hostname + " > /etc/hostname && " +
          "mv /etc/hosts /etc/hosts." + timestamp + " && " +
          "echo -e \"127.0.0.1\tlocalhost localhost.localdomain\n" +
                     mainPublicIP + "\t" + fqdn + " " + hostname + "\" > /etc/hosts && " +
          "hostname -F /etc/hostname && "

        val dnsResult = client.runScriptOnNode(nodeId, script)

        if (dnsResult.getExitCode != 0) {
          logger.error("DNS run failed: " + dnsResult.getOutput)
          return
        }

        val authParams = if (credential.startsWith("-----BEGIN RSA")) {
          val keyFile = FileUtils.writeTempFile("nodedata", credential)

          "-i " + keyFile.getCanonicalPath
        } else {
          "-P " + credential
        }

        // Bootstrap chef
        val cmd = "knife bootstrap %s -N %s -x %s %s -E %s -r %s".format(mainPublicIP, fqdn, identity, authParams, env, config.getString(serverType + ".roles", "role[base]"))
        cmd.!(ProcessLogger(println(_)))
        logger.info("Completed provisioning on " + fqdn)
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