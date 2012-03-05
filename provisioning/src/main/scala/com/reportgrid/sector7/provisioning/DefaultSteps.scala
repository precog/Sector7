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
    val hostname = fqdn.split('.').head

    logger.info("Fixing DNS for " + fqdn)

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
        logger.info("DNS fix complete on " + fqdn)
        true
      }
    }.getOrElse {
      logger.error("Node %s doesn't have a public IP!".format(node.getId))
      false
    }
  })

  val runAptUpdates = BuildStep("Run APT updates", { (fqdn, node, client) =>
    logger.info("Running APT updates on " + fqdn)

    val script = "sudo aptitude update && sudo aptitude -y safe-upgrade"
    
    val result = client.runScriptOnNode(node.getId, script)
    
    if (result.getExitCode != 0) {
      logger.error("APT update failed: " + result.getOutput)
      false
    } else {
      logger.info("APT update complete on " + fqdn)
      true
    }
  })

  private def procLogger = new ProcessLogger {
    private var count = 0
    def err(s: => String) { logger.error(s) }

    def out(s: => String) {
      count +=1
      if (count % 100 == 0) {
        logger.info(s)
      } else {
        logger.trace(s)
      }
    }

    def buffer[T](f: => T) = f
  }

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

        logger.info("Pausing on bootstrap attempt on " + fqdn)
        Thread.sleep(60000)

        // Bootstrap chef
        logger.info("Bootstrapping " + fqdn)
                                                      
        var rc = 100 // Badness
        while (rc != 0) {
          val cmd = "knife bootstrap %s -N %s -x %s %s -E %s -r %s --sudo".format(mainPublicIP, fqdn, identity, authParams, environment, roles.getOrElse("role[base]"))
          logger.info("Making bootstrap run: " + cmd)
          rc = cmd.!(procLogger)
          if (rc != 100) {
            logger.info("Completed bootstrap on %s : %d".format(fqdn, rc))
          } else { 
            logger.info("Going to try chef again in 15s on " + fqdn); Thread.sleep(15000)
          }
        }
        rc == 0
      }.getOrElse {
        logger.error("Node %s doesn't have a public IP!".format(node.getId))
        false
      }
    }
  )
}







