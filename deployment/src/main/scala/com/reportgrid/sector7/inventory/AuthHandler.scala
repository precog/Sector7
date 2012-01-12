package com.reportgrid.sector7.inventory

import blueeyes.concurrent.Future
import blueeyes.core.service._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.http.{HttpFailure, HttpRequest}
import scalaz.{Failure, Validation}
import com.weiglewilczek.slf4s.Logger
import com.reportgrid.sector7.utils.RequestUtils

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 2:12 PM
 */
class AuthRequiredService[A, B](token : String, val delegate : HttpService[A, Future[B]], log : Logger)(implicit err : (HttpFailure, String) => B)
extends DelegatingService[A, Future[B], A, Future[B]] with RequestUtils {
  def metadata = None

  val service  : HttpRequest[A] => Validation[NotServed,Future[B]] = (request : HttpRequest[A]) => {
    request.headers.get("Authtoken") match {
      case None => {
        log.warn("Request without auth token from " + request.remoteHostIp)
        Failure(DispatchError(BadRequest, "Unauthorized"))
      }
      case Some(headerToken) =>
        if (headerToken == token) {
          delegate.service(request)
        } else {
          log.warn("Invalid auth token from " + request.remoteHostIp)
          Failure(DispatchError(BadRequest,"Unauthorized"))
        }
    }
  }
}