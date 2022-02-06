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
package gov.nasa.race.ui

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.http.{CachedFileAssetMap, ResponseData, RaceRouteInfo}
import scalatags.Text


object UiRoute extends CachedFileAssetMap {
  def sourcePath: String = "./race-client-ui/src/main/resources/gov/nasa/race/ui"
  def uiDefaultTheme: String = "ui_theme_dark.css"
}

/**
  * a RaceRouteInfo that serves race-ui-client content
  */
trait UiRoute extends  RaceRouteInfo {
  val uiThemeCss = config.getStringOrElse("ui-theme", UiRoute.uiDefaultTheme)

  //--- route handling

  override def route: Route = uiRoute ~ super.route

  def uiRoute: Route = {
    get {
      path("ui.js") {
        complete( ResponseData.js( UiRoute.getContent("ui.js")))
      } ~ path("ui_util.js") {
        complete( ResponseData.js( UiRoute.getContent("ui_util.js")))
      } ~ path("ui_data.js") {
        complete( ResponseData.js( UiRoute.getContent("ui_data.js")))
      } ~ path("ui.css") {
        complete( ResponseData.css( UiRoute.getContent("ui.css")))
      } ~ path("ui_theme_dark.css") {
        complete( ResponseData.css( UiRoute.getContent("ui_theme_dark.css")))
      }
    }
  }


  //--- document content

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiResources

  def uiResources: Seq[Text.TypedTag[String]] = {
    Seq(
      cssLink(uiThemeCss), // TODO - this should be configurable/request dependent
      cssLink("ui.css"),

      extModule("ui_data.js"),  // TODO - should this be optional?
      extModule("ui_util.js"),
      extModule("ui.js")
    )
  }
}
