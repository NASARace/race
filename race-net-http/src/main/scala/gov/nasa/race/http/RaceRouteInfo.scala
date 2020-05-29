/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentActor, RaceDataConsumer}

/**
  * base type for route infos, which consist of a akka.http Route and an optional RaceActor
  * to collect data used for the associated response content
  *
  * concrete RaceRouteInfo implementations have to provide a constructor that takes 2 args:
  *     (parent: ParentActor, config: Config)
  */
trait RaceRouteInfo {
  val parent: ParentActor
  val config: Config
  val name = config.getStringOrElse("name", getClass.getSimpleName)
  val requestPrefix: String = config.getStringOrElse("request-prefix","")

  // this is the main function that defines the public (user) routes
  def route: Route

  // this can extend public routes with private ones used from within server responses
  // (such as login/logout and common asset routes)
  def completeRoute: Route = route

  def shouldUseHttps = false

  def notFoundRoute: Route = {
    extractUri { uri =>
      complete(StatusCodes.NotFound, s"document $uri not found")
    }
  }

  def debug(f: => String) = gov.nasa.race.core.debug(f)(parent.log)
  def info(f: => String) = gov.nasa.race.core.info(f)(parent.log)
  def warning(f: => String) = gov.nasa.race.core.warning(f)(parent.log)
  def error(f: => String) = gov.nasa.race.core.error(f)(parent.log)
}


/**
  * a RaceRouteInfo that has an associated RaceRouteActor which sets the published content
  * from information received via RACE channel messages
  */
trait SubscribingRaceRoute extends RaceRouteInfo with RaceDataConsumer




