package com.reportgrid.sector7.tools

import scala.collection.JavaConverters._

import org.jclouds.compute.ComputeService
import net.lag.configgy.Configgy
import org.jclouds.ec2.domain.InstanceType
import com.reportgrid.sector7.provisioning._
import org.jclouds.ec2.compute.options.EC2TemplateOptions
import org.jclouds.aws.ec2.compute.AWSEC2ComputeService
import com.reportgrid.sector7.utils.{FileUtils, JCloudsFactory}
import java.util.concurrent.Executors
import sys.process.Process
import com.weiglewilczek.slf4s.Logging
import org.jclouds.ec2.compute.EC2ComputeService
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.io.Payloads
import java.lang.String
import collection.immutable.List
import org.jclouds.loadbalancer.LoadBalancerService

/**
 * Copyright 2012, ReportGrid, Inc.
 *
 * Created by dchenbecker on 1/12/12 at 11:08 AM
 */

// TODO: Need iptables on all of these
object CreateCluster extends Logging {
  object IntVal {
    def unapply(s : String) = try { Some(s.toInt) } catch { case _ => None }
  }

  val defaultExecutor = Executors.newFixedThreadPool(15)

  object Template {
    def isEC2(client : ComputeService) = (client.isInstanceOf[EC2ComputeService] || client.isInstanceOf[AWSEC2ComputeService])

    // TODO: Make this not EC2-specific. ami-ad36fbc4 is EBS because that's a requirement for t1.micro instances
    def micro(name : String, client : ComputeService, credentials : String) = {
      val template = if (isEC2(client)) {
        val underlying = client.templateBuilder().hardwareId(InstanceType.T1_MICRO).imageId("us-east-1/ami-ad36fbc4").os64Bit(true).build()
        underlying.getOptions.asInstanceOf[EC2TemplateOptions].keyPair("ec2-keypair").overrideLoginCredentialWith(credentials).inboundPorts(22, 80)
        underlying
      } else {
        client.templateBuilder().minRam(250).minCores(2).build()
      }
      BuildTemplate(name, template)
    }
    
    def large(name : String,  client : ComputeService, credentials : String) = {
      val template = if (isEC2(client)) {
        val underlying = client.templateBuilder().hardwareId(InstanceType.M1_LARGE).imageId("us-east-1/ami-1136fb78").os64Bit(true).build()
        underlying.getOptions.asInstanceOf[EC2TemplateOptions].keyPair("ec2-keypair").overrideLoginCredentialWith(credentials).inboundPorts(22, 80)
        underlying
      } else {
        client.templateBuilder().minRam(8000).build()
      }
      BuildTemplate(name, template)
    }

    def xlarge(name : String, client : ComputeService, credentials : String) = {
      val template = if (isEC2(client)) {
        val underlying = client.templateBuilder().hardwareId(InstanceType.M1_XLARGE).imageId("us-east-1/ami-1136fb78").os64Bit(true).build()
        underlying.getOptions.asInstanceOf[EC2TemplateOptions].keyPair("ec2-keypair").overrideLoginCredentialWith(credentials).inboundPorts(22, 80)
        underlying
      } else {
        client.templateBuilder().minRam(30000).build()
      }
      BuildTemplate(name, template)
    }
  }

  def setupEnv(name: String) {
    val envFile = FileUtils.writeTempFile(name + "-env", ChefEnvBuilder.clusterConfig(name), Some(".json"))

    logger.info("Building env from " + envFile.getCanonicalPath)

    // TODO : Use jclouds-chef instead of knife
    if (Process("knife", List("environment", "from", "file", envFile.getCanonicalPath)).! != 0) {
      logger.error("Failed to create %s env".format(name))
      sys.exit(1)
    }
  }

  def dieOnFailures(results: List[BuildResult]) : List[BuildResult] = {
    if (! results.forall(_.failures.isEmpty)) {
      logger.error("Failed server setup: " + (results.map { result => result.name + result.failures.mkString("\n", "\n  ", "\n")}))
      sys.exit(1)
    }
    results
  }

  def createConfigServers(name: String, client : ComputeService, credentials : String) : List[BuildResult] = {
    logger.info("Creating config servers")
    
    val template = Template.micro(name, client, credentials)

    // Need three config servers
    val requests = (1 to 3).map { id =>
      BuildRequest("config%02d.%s.reportgrid.com".format(id, name), template, List(
        DefaultSteps.fixDns, DefaultSteps.runAptUpdates, DefaultSteps.chefBoot(name, Some("role[default],role[mongodb-config-server]"))
      ))
    }.toList

    dieOnFailures(Provisioner.provision(requests, client, defaultExecutor))
  }

  // Returns the generated nodes results
  def createShard(id: Int, name : String, client: ComputeService, credentials : String) : List[BuildResult] = {
    logger.info("Creating shards")
    
    val requests = (1 to 2).map { memberId =>
      BuildRequest("shard%02d-%02d.%s.reportgrid.com".format(id, memberId, name), Template.xlarge(name, client, credentials), List(
        DefaultSteps.fixDns, DefaultSteps.runAptUpdates, DefaultSteps.chefBoot(name, Some("role[base],role[mongodb-shard-server],role[mongodb-replset-server],recipe[mongodb::mongos]"))
      ))
    }.toList ++ List(
      BuildRequest("shard%02d-03.%s.reportgrid.com".format(id, name), Template.micro(name, client, credentials), List(
        DefaultSteps.fixDns, DefaultSteps.runAptUpdates, DefaultSteps.chefBoot(name, Some("role[base],role[mongodb-shard-server],role[mongodb-replset-server],role[mongodb-arbiter],recipe[mongodb::mongos]"))
      ))
    )

    val results = dieOnFailures(Provisioner.provision(requests, client, defaultExecutor))

    logger.info("Done with shard member setup")


    logger.info("Activating replset")
    // Activate the replset
    results.find(_.name == "shard%02d-01.%s.reportgrid.com".format(id, name)).foreach { primary =>
      val script = """
        echo 'rs.initiate({ _id : "shard%1$02d", members : [ { _id : 0, host : "shard%1$02d-01.%2$s.reportgrid.com:27018" }, { _id : 1, host : "shard%1$02d-02.%2$s.reportgrid.com:27018" }, { _id : 2, host : "shard%1$02d-03.%2$s.reportgrid.com:27018", arbiterOnly : true } ] })' | mongo --port 27018
      """.format(id, name)

      val response = client.runScriptOnNode(primary.node.getId, script)

      if (response.getExitCode != 0) {
        logger.error("Failed to activate replset")
        sys.exit(1)
      }
    }
    logger.info("Replset activated")

    // Need to give the replset time to activate before adding to the shard
    logger.info("Waiting for replset to come online")
    Thread.sleep(60000)

    // Add the shard to the cluster
    logger.info("Adding shard to cluster")
    results.find(_.name == "shard%02d-01.%s.reportgrid.com".format(id, name)).foreach { primary =>
      // Not port 27018 because we need to use mongos, but need port 27018 on shard config
      val script = """
        echo 'db.runCommand( { addshard : "shard%1$02d/shard%1$02d-01.%2$s.reportgrid.com:27018,shard%1$02d-02.%2$s.reportgrid.com:27018" });' | mongo admin
      """.format(id, name)

      val response = client.runScriptOnNode(primary.node.getId,  script)

      if (response.getExitCode != 0) {
        logger.error("Failed to add shard")
        sys.exit(1)
      }
    }
    logger.info("Shard added to cluster")

    results
  }

  def setupDatabase(node : NodeMetadata, client: ComputeService) {
    logger.info("Setting up database and collections")
    
    // Copy the script to one of the nodes
    val createScript = this.getClass.getClassLoader.getResourceAsStream("com/reportgrid/sector7/provisioning/createAnalytics1.js")

    try {
      val ssh = client.getContext.utils().sshForNode().apply(node)
      
      try {
        ssh.connect()

        ssh.put("/tmp/createAnalytics1.js", Payloads.newInputStreamPayload(createScript))
      } finally {
        ssh.disconnect()
      }

      client.runScriptOnNode(node.getId, """mongo analytics1 < /tmp/createAnalytics1.js""")
    } catch {
      case e => logger.error("Error setting up database", e)
    }
  }

  def createAppServers(count: Int, envName : String,  client: ComputeService, credentials: String, startIndex : Int = 1) : List[BuildResult] = {
    logger.info("Creating app servers")
    
    val template = Template.large(envName, client, credentials)

    val requests = (startIndex to (startIndex + count - 1)).map { memberId =>
      BuildRequest("app%02d.%s.reportgrid.com".format(memberId, envName), template, List(
        DefaultSteps.fixDns, DefaultSteps.runAptUpdates, DefaultSteps.chefBoot(envName, Some("role[base],role[appserverV2],role[monitored],role[vizserver]"))
      ))
    }
    
    dieOnFailures(Provisioner.provision(requests.toList, client, defaultExecutor))
  }

  def createLB(envName: String, servers: List[NodeMetadata], client: LoadBalancerService, credentials: String) {
    logger.info("Creating load balancer")
    val result = client.createLoadBalancerInLocation(null, "lb-" + envName, "HTTP", 80, 80, servers.asJava)
    logger.info("LB created at " + result.getAddresses)
  }

  def main(args: Array[String]) {
    args.splitAt(3) match {
      case (Array(config, credentialFile, envName), otherArgs) => {
        Configgy.configure(config)
        val context = JCloudsFactory.computeContextForProvider(Configgy.config)
        val client = context.getComputeService
//        val lbClient = JCloudsFactory.lbContextForProvider(Configgy.config).getLoadBalancerService

        val credentials = FileUtils.readFile(credentialFile)

        otherArgs match {
          case Array("addConfigServers") => {
            logger.info("Adding mongodb config servers to " + envName)
            createConfigServers(envName, client, credentials)
            logger.info("Config servers complete")
          }
          case Array("addShard", IntVal(count), IntVal(startId)) => {
            logger.info("Adding shards to " + envName)
            (startId to (startId + count - 1)).foreach(createShard(_, envName, client, credentials))
            logger.info("Shards complete")
          }
          case Array("addAppServers", IntVal(count), IntVal(startIndex)) => {
            logger.info("Adding app servers to " + envName)
            createAppServers(count, envName, client, credentials, startIndex)
            logger.info("App servers complete")
          }
          case Array(IntVal(shardCount), IntVal(appCount)) => {

            logger.info("Starting full cluster build")
            setupEnv(envName)

            createConfigServers(envName, client, credentials)

            val shards = (1 to shardCount).map(createShard(_, envName, client, credentials))

            logger.info("Waiting for shard completion")
            Thread.sleep(60000)

            setupDatabase(shards.head.head.node, client)

            val appServers = createAppServers(appCount, envName, client, credentials)

//            createLB(envName, appServers.map(_.node), lbClient, credentials)
          }
        }
      }
      case _ => logger.info("Usage : CreateCluster <config> <credential file> <env name> <command>")
    }
  }
}
