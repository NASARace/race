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
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import scalatags.Text
import scalatags.Text.all.{script, _}

/**
  * RaceRouteInfo that serves a single page application, i.e. manages a single (possibly dynamic) Document
  */
trait DocumentRaceRoute extends RaceRouteInfo {

  protected def completeSpaRequest: Route = {
    complete( HttpEntity(ContentTypes.`text/html(UTF-8)`, getDocument))
  }

  def documentRoute: Route = {
    get {
      path(requestPrefixMatcher) {
        completeSpaRequest
      }
    }
  }

  override def route: Route = documentRoute ~ super.route

   /*
    * the content creators to be provided by the concrete type
    * TODO - do we need to pass in requestUri and remoteAddr to allow for client specific documents?
    */


  protected def getDocument: String = {
    html(
      scalatags.Text.all.head( getPreambleHeaderFragments, getHeaderFragments ),
      body(onload:="main.initialize()", onunload:="main.shutdown()")( getPreambleBodyFragments, getBodyFragments )
    ).render
  }
}

/**
  * a DocumentRaceRoute that adds a main module and css
  */
trait MainDocumentRoute extends DocumentRaceRoute {
  val mainModule: String
  val mainCss: String

  def mainModuleContent: Array[Byte]
  def mainCssContent: Array[Byte]

  def mainResourceRoute: Route = {
    get {
      path(mainCss) {
        complete( ResponseData.css( mainCssContent))
      } ~ path(mainModule) {
        complete( ResponseData.js( mainModuleContent))
      }
    }
  }

  override def route: Route = mainResourceRoute ~ super.route

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ mainResources

  def mainResources: Seq[Text.TypedTag[String]] = {
    Seq(
      link(rel:="stylesheet", tpe:="text/css", href:=mainCss),
      script(src:=mainModule, tpe:="module")
    )
  }
}