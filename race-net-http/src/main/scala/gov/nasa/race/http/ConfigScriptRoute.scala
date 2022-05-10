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

import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaTypes, Uri}
import akka.http.scaladsl.server.Directives.{complete, extractUri, headerValueByType, path}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress

/**
  * a RaceRoute that needs to serve a 'config.js' script (ECMA module) as part of the content
  */
trait ConfigScriptRoute extends RaceRouteInfo {

  def configScript: Text.TypedTag[String] = script(src:="config.js", tpe:="module")

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments :+ configScript

  /**
    * the content creator to be provided by the concrete type
    * Note this can use the remoteAddr or requestUri parameters to discriminate between users/devices
    *
    * note we provide an identity element here so that we can accumulate the config
    */
  def getConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = ""

  protected def completeConfigRequest (requestUri: Uri, remoteAddr: InetSocketAddress): Route = {
    complete {
      HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getConfig(requestUri, remoteAddr))
    }
  }

  def configRoute: Route = {
    get {
      path("config.js") { // [AUTH] - contains session tokens & urls
        extractUri { uri =>
          headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
            completeConfigRequest(uri, sockConn.remoteAddress)
          }
        }
      }
    }
  }

  override def route: Route = configRoute ~ super.route
}

/**
  * a ConfigScriptRaceRoute that requires user authentication (usually done in the document request)
  */
trait AuthConfigScriptRoute extends ConfigScriptRoute with AuthRaceRoute {

  override def completeConfigRequest (requestUri: Uri, remoteAddr: InetSocketAddress): Route = {
    completeAuthorized() {
      HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getConfig(requestUri, remoteAddr))
    }
  }
}
