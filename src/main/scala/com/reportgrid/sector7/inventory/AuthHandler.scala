package com.reportgrid.sector7.inventory

import blueeyes.json.JsonAST._
import blueeyes.concurrent.Future
import blueeyes.core.service._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.core.http.{HttpStatus, HttpRequest, HttpResponse}

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 11/4/11 at 2:12 PM
 */


// TODO: Fix this to be a service wrapper instead of just a function generator
object AuthHandler {
  def apply(token : String)(f : HttpRequest[Future[JValue]] => Future[HttpResponse[JValue]]) : HttpRequest[Future[JValue]] => Future[HttpResponse[JValue]] =
    (request : HttpRequest[Future[JValue]]) => request.headers.get("Authtoken") match {
      case Some(sentToken) if token == sentToken => f(request)
      case Some(_) => Future.sync(HttpResponse(HttpStatus(BadRequest, "Invalid auth token specified")))
      case None => Future.sync(HttpResponse(HttpStatus(BadRequest, "Invalid request")))
    }
}