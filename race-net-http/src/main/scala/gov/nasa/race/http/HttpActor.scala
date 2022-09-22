/*
 * Copyright (c) 2022, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nasa.race.http

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, RequestEntity}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.Materializer
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActor

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * an actor that uses Akka-http
 *
 * used to factor out common akka-http objects and methods
 */
trait HttpActor extends RaceActor {

  val maxRequestSize = config.getIntOrElse("max-request-size", 5*1024*1000)
  val maxRequestTimeout = config.getFiniteDurationOrElse("max-request-timeout", 15.seconds)

  val maxConnectingTimeout = config.getFiniteDurationOrElse("max-connecting-timeout", 15.seconds)

  val clientSettings = {
    val origSettings = ClientConnectionSettings(system.settings.config)
    ConnectionPoolSettings(system.settings.config).withConnectionSettings( origSettings.withConnectingTimeout(maxConnectingTimeout))
  }

  // we might get ambiguities if those are public or protected
  private implicit val materializer: Materializer = Materializer.matFromSystem(context.system)
  private implicit val executionContext = scala.concurrent.ExecutionContext.global
  private val http = Http(context.system)

  def httpRequest [U] (req: HttpRequest)(f: Try[HttpResponse]=>U): Unit = {
    http.singleRequest(req, settings = clientSettings).onComplete(f)
  }

  def httpRequest [U] (uri: String, method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty)(f: Try[HttpResponse]=>U): Unit = {
    httpRequest(HttpRequest( method, uri, headers, entity))(f)
  }

  def httpRequestStrict [U] (req: HttpRequest)(f: Try[HttpEntity.Strict]=>U): Unit = {
    http.singleRequest(req).flatMap(_.entity.toStrict(maxRequestTimeout, maxRequestSize)).onComplete(f)
  }

  def httpRequestStrict [U] (uri: String, method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty)(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrict( HttpRequest( method, uri, headers, entity))(f)
  }

  def toStrictEntity [U] (resp: HttpResponse)(f: Try[HttpEntity.Strict]=>U): Unit = {
    resp.entity.toStrict( maxRequestTimeout, maxRequestSize).onComplete(f)
  }

  def awaitHttpRequest [U] (req: HttpRequest)(f: PartialFunction[Try[HttpResponse],U]): Unit = {
    Await.ready( http.singleRequest(req, settings = clientSettings).andThen(f), maxRequestTimeout)
  }
}
