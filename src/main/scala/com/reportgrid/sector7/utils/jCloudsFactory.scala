package com.reportgrid.sector7.utils

import net.lag.configgy.Config
import org.jclouds.compute.ComputeServiceContextFactory
import com.google.common.collect.ImmutableSet
import com.google.inject.Module
import org.jclouds.ssh.jsch.config.JschSshClientModule
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/26/11 at 9:16 PM
 */


object JCloudsFactory extends ConfigValidator {
  private val PROVIDER_KEY = "jclouds.provider"
  private val USER_KEY = "jclouds.user"
  private val APIKEY_KEY = "jclouds.apikey"

  val parameters =
    List(StringParameter(PROVIDER_KEY, true, Some("The jClouds provider to use to provision the server")),
         StringParameter(USER_KEY, true, Some("The user account for API access to the provider")),
         StringParameter(APIKEY_KEY, true, Some("The key for API access to the provider")))

  def contextForProvider(conf : Config) = {
    val options = ImmutableSet.of[Module](new JschSshClientModule, new SLF4JLoggingModule)

    (new ComputeServiceContextFactory).createContext(conf.getString(PROVIDER_KEY).get,
                                                     conf.getString(USER_KEY).get,
                                                     conf.getString(APIKEY_KEY).get,
                                                     options)
  }
}