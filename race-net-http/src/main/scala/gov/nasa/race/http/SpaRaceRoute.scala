/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{complete, get, path}
import akka.http.scaladsl.server.Route

/**
  * RaceRouteInfo that serves a single page application, i.e. manages a single (possibly dynamic) Document
  */
trait SpaRaceRoute extends RaceRouteInfo {

  protected def completeSpaRequest: Route = {
    complete( HttpEntity(ContentTypes.`text/html(UTF-8)`, getDocument()))
  }

  def documentRoute: Route = {
    get {
      path(requestPrefixMatcher) {
        completeSpaRequest
      }
    }
  }

  /**
    * the content creator to be provided by the concrete type
    * TODO - do we need to pass in requestUri and remoteAddr to allow for client specific documents?
    */
  protected def getDocument(): String
}

/**
  * SpaRaceRoute requiring user-authentication
  */
trait AuthSpaRaceRoute extends AuthRaceRoute with SpaRaceRoute {

  override def completeSpaRequest: Route = {
    completeAuthorized() {
      HttpEntity(ContentTypes.`text/html(UTF-8)`, getDocument())
    }
  }
}