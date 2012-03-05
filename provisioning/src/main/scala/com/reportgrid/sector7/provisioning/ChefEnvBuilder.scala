package com.reportgrid.sector7.provisioning

/**
 * Copyright 2012, ReportGrid, Inc.
 *
 * Created by dchenbecker on 1/12/12 at 10:40 AM
 */

object ChefEnvBuilder {

  // Dirty hack to just get json working
  def clusterConfig(name : String) : String = {
    val clusterJson = """
    {
      "name" : "%1$s",
      "description" : "%1$s cluster environment",
      "chef_type" : "environment",
      "json_class" : "Chef::Environment",
      "override_attributes" : {
        "postfix" : {
          "root_email" : "operations@reportgrid.com"
        },
        "apache" : {
          "listen_ports" : [ "20000" ]
        },
        "mongodb" : {
          "use_fqdn_prefix_for_replset" : true,
          "config_servers" : [ "config01.%1$s.reportgrid.com:27019",
                               "config02.%1$s.reportgrid.com:27019",
                               "config03.%1$s.reportgrid.com:27019" ]
        }
      }
    }
  """

  clusterJson.format(name)
  }
}