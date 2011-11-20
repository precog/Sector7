package com.reportgrid.sector7.utils

import blueeyes.core.http.HttpRequest

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/11/11 at 9:00 AM
 */


trait RequestUtils {
  class ExtendedRequest[A](request : HttpRequest[A]) {
    def remoteHostIp = request.remoteHost.map(_.getHostAddress).getOrElse("unknown IP")
  }

  implicit def httpRequestToExtendedRequest[T](request : HttpRequest[T]) : ExtendedRequest[T] = new ExtendedRequest[T](request)
}