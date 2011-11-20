package com.reportgrid.sector7.provisioning

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/18/11 at 9:06 PM
 */
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.JavaConverters._
import com.weiglewilczek.slf4s.Logging
import sys.process._
import com.reportgrid.sector7.utils.FileUtils

object DefaultSteps extends Logging {
  private val timeFormatter = new SimpleDateFormat("yyyyMMddHHmmss") 
  def timestamp = timeFormatter.format(new Date)
  
  val fixDns = BuildStep("Fix DNS", { (fqdn, node, client) =>
    val serial = timestamp
    val hostname = fqdn.split('.').last

    node.getPublicAddresses.asScala.headOption.map { mainPublicIP =>
      val script = "mv /etc/hostname /etc/hostname." + serial + " && echo " + hostname + " > /etc/hostname && " +
      "mv /etc/hosts /etc/hosts." + serial + " && " +
      "echo -e \"127.0.0.1\tlocalhost localhost.localdomain\n" +
         mainPublicIP + "\t" + fqdn + " " + hostname + "\" > /etc/hosts && " +
      "hostname -F /etc/hostname && hostname"

      val result = client.runScriptOnNode(node.getId, script)

      if (result.getExitCode != 0) {
        logger.error("DNS run failed: " + result.getOutput)
        false
      } else {
        true
      }
    }.getOrElse {
      logger.error("Node %s doesn't have a public IP!".format(node.getId))
      false
    }
  })

  def chefBoot(environment : String, roles : Option[String]) = BuildStep("Boot chef",
    { (fqdn, node, client) =>
      node.getPublicAddresses.asScala.headOption.map { mainPublicIP =>
        val (credential,identity) = (node.getCredentials.credential,node.getCredentials.identity)
        val authParams = if (credential.startsWith("-----BEGIN RSA")) {
          val keyFile = FileUtils.writeTempFile("nodedata", credential)

          "-i " + keyFile.getCanonicalPath
        } else {
          "-P " + credential
        }

        // Bootstrap chef
        val cmd = "knife bootstrap %s -N %s -x %s %s -E %s -r %s".format(mainPublicIP, fqdn, identity, authParams, environment, roles.getOrElse("role[base]"))
        cmd.!(ProcessLogger(println(_))) == 0
      }.getOrElse {
        logger.error("Node %s doesn't have a public IP!".format(node.getId))
        false
      }
    }
  )
}







