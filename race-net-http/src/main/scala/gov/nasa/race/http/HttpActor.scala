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
import akka.stream.{BufferOverflowException, Materializer}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActor

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * an actor that uses Akka-http
 *
 * used to factor out common akka-http objects and methods
 */
trait HttpActor extends RaceActor {

  val maxRequestSize = config.getIntOrElse("max-request-size", 5*1024*1000)
  val maxRequestTimeout = config.getFiniteDurationOrElse("max-request-timeout", 15.seconds)
  val maxConnectingTimeout = config.getFiniteDurationOrElse("max-connecting-timeout", 15.seconds)

  val retryDelay = config.getFiniteDurationOrElse("retry-delay", 2.seconds)
  val maxRetry = config.getIntOrElse("max-retry", 3)

  val clientSettings = {
    val origSettings = ClientConnectionSettings(system.settings.config)
    ConnectionPoolSettings(system.settings.config).withConnectionSettings( origSettings.withConnectingTimeout(maxConnectingTimeout))
  }

  // we might get ambiguities if those are public or protected
  private implicit val materializer: Materializer = Materializer.matFromSystem(context.system)
  private implicit val executionContext = scala.concurrent.ExecutionContext.global
  private val http = Http(context.system)

  def httpRequest [U] (req: HttpRequest)(f: Try[HttpResponse]=>U): Unit = {
    http.singleRequest(req, settings = clientSettings).onComplete {
      case v@Success(resp) =>
        f(v)
        resp.discardEntityBytes()
      case x@Failure(_) => f(x)
    }

    //http.singleRequest(req, settings = clientSettings).onComplete(f)
    //http.singleRequest(req, settings = clientSettings).andThen { case res => f(res) }.andThen { case res => res.get.discardEntityBytes() }
  }

  def httpRequestWithRetry [U] (req: HttpRequest, retries: Int)(f: Try[HttpResponse]=>U): Unit = {
    httpRequest(req){
      case Failure(box: BufferOverflowException) =>
        if (retries <= 0) throw box // rethrow, let caller handle it
        warning(s"request overload of ${req.uri}, remaining retries: $retries")
        scheduleOnce(retryDelay)(httpRequestWithRetry(req, retries-1)(f))
      case v => f(v)
    }
  }

  def httpRequest [U] (uri: String,
                       method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty
                      )(f: Try[HttpResponse]=>U): Unit = {
    httpRequest( HttpRequest( method, uri, headers, entity))(f)
  }

  def httpRequestWithRetry [U] (uri: String,
                                method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty,
                                retries: Int = maxRetry
                               )(f: Try[HttpResponse]=>U): Unit = {
    httpRequestWithRetry( HttpRequest( method, uri, headers, entity), retries)(f)
  }

  def httpRequestStrict [U] (req: HttpRequest)(f: Try[HttpEntity.Strict]=>U): Unit = {
    http.singleRequest(req).flatMap(_.entity.toStrict(maxRequestTimeout, maxRequestSize)).onComplete(f)
  }

  // retry after delay if we get a pending request buffer overflow
  def httpRequestStrictWithRetry [U](req: HttpRequest, retries: Int)(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrict(req){
      case Failure(box: BufferOverflowException) =>
        if (retries <= 0) throw box // rethrow, let caller handle it
        warning(s"request overload of ${req.uri}, remaining retries: $retries")
        scheduleOnce(retryDelay)(httpRequestStrictWithRetry(req, retries-1)(f))
      case v => f(v)
    }
  }

  def httpRequestStrict [U] (uri: String,
                             method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty
                            )(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrict( HttpRequest( method, uri, headers, entity))(f)
  }

  def httpRequestStrictWithRetry [U] (uri: String,
                                      method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty,
                                      retries: Int=maxRetry
                                     )(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrictWithRetry( HttpRequest( method, uri, headers, entity), retries)(f)
  }

  def toStrictEntity [U] (resp: HttpResponse)(f: Try[HttpEntity.Strict]=>U): Unit = {
    resp.entity.toStrict( maxRequestTimeout, maxRequestSize).onComplete(f)
  }

  def awaitHttpRequest [U] (req: HttpRequest)(f: PartialFunction[Try[HttpResponse],U]): Unit = {
    Await.ready( http.singleRequest(req, settings = clientSettings).andThen(f), maxRequestTimeout)
  }
}
