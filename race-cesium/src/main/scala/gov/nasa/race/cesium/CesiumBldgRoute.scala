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
package gov.nasa.race.cesium

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.http.{CachedFileAssetRoute, DocumentRoute}
import gov.nasa.race.ui.extModule
import scalatags.Text

/**
  * a Cesium Route that adds an OSM buildings layer
  *
  * TODO - should be part of a generic CesiumPrimitiveRoute
  *
  * TODO - will have to handle per-building info
  */
trait CesiumBldgRoute extends CesiumRoute with DocumentRoute with CachedFileAssetRoute
{
  //--- resources & fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("ui_cesium_bldg.js")
  )

  //--- route

  def bldgRoute: Route = {
    fileAssetPath("ui_cesium_bldg.js")
  }

  override def route: Route = bldgRoute ~ super.route
}
