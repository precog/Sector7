package com.reportgrid.sector7.utils

import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTime

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 10:12 PM
 */


object TimeUtils {
  val format = DateTimeFormat.forPattern("YYYYMMddHHmmssSSS")

  def timestamp = format.print(new DateTime)
}