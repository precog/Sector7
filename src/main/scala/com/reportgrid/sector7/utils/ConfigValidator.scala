package com.reportgrid.sector7.utils

import net.lag.configgy.Config

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/26/11 at 9:18 PM
 */

/**
 * This class represents a given configuration parameter
 */
abstract class Parameter[A] {
  val name : String
  val required : Boolean
  val description : Option[String]
  def extract : Config => Option[A]
}

object Parameter {
  def unapply(p : Parameter[_]) = Some((p.name, p.required, p.description))
}

case class StringParameter(name : String,
                           required : Boolean,
                           description : Option[String]) extends Parameter[String] {
  def extract = _.getString(this.name)
}

/**
 * Classes implementing this trait can check provided Config
 * instances and return sequences of missing parameters with
 * descriptions.
 */
trait ConfigValidator {
  /**
   * A sequence of parameters that this validator uses.
   */
  def parameters : Seq[Parameter[_]]

  /**
   * Validate the given configuration and return a sequence of any
   * missing required parameters along with an optional description of
   * the parameter
   */
  def check(conf : Config) : Seq[(String,Option[String])] = {
    parameters.map(p => (p, p.extract(conf))).filter(_._2.isEmpty).map(r => (r._1.name, r._1.description))
  }
}