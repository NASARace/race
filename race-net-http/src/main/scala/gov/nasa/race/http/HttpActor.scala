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
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, HttpMethods, HttpRequest, RequestEntity}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.Materializer
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActor

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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

  protected implicit val materializer: Materializer = Materializer.matFromSystem(context.system)
  protected implicit val executionContext = scala.concurrent.ExecutionContext.global

  protected val http = Http(context.system)

  def httpGetRequestStrict (uri: String, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty): Future[HttpEntity.Strict] = {
    http.singleRequest( HttpRequest( HttpMethods.GET, uri, headers, entity)).flatMap{ resp =>
      resp.entity.toStrict( maxRequestTimeout, maxRequestSize)
    }
  }
}
