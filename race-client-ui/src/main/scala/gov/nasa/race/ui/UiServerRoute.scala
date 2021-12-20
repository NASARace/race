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

import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.common.CachedFile
import gov.nasa.race.util.ClassUtils
import gov.nasa.race.http.RaceRouteInfo


object UiServerRoute {
  private var pathMap: Map[String, Map[String,CachedFile]] = Map.empty

  def uiSourcePath: String = "./race-client-ui/src/main/resources/gov/nasa/race/ui"
  def uiDefaultTheme: String = "ui_theme_dark.css"

  def getCachedFile (path: String, fileName: String): CachedFile = {
    new CachedFile( path + '/' + fileName, ClassUtils.getResourceAsBytes(getClass(), fileName).getOrElse(Array.empty))
  }

  def getContent (path: String, fileName: String):  Array[Byte] = {
    pathMap.get(path) match {
      case Some(assetMap) => // path already known
        assetMap.get(fileName) match {
          case Some (cf) =>
            cf.getContentAsBytes()
          case None =>
            val cf = getCachedFile(path,fileName)
            pathMap = pathMap + (path -> (assetMap + (fileName -> cf)))
            cf.getContentAsBytes()
        }
      case None => // no assets for this path yet
        val cf = getCachedFile(path,fileName)
        pathMap = pathMap + (path -> Map((fileName -> cf)))
        cf.getContentAsBytes()
    }
  }
}

/**
  * a RaceRouteInfo that serves race-ui-client content
  */
trait UiServerRoute extends  RaceRouteInfo {
  val uiPath: String // to be provided by concrete type

  def uiAssetRoute: Route = {
    path("ui.js") {
      complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`),
        UiServerRoute.getContent(uiPath,"ui.js")))
    } ~ path("ui_util.js") {
      complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`),
        UiServerRoute.getContent(uiPath,"ui_util.js")))
    } ~ path("ui_data.js") {
      complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`),
        UiServerRoute.getContent(uiPath,"ui_data.js")))
    } ~ path("ui.css") {
      complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`),
        UiServerRoute.getContent(uiPath,"ui.css")))
    } ~ path("ui_theme_dark.css") {
      complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`),
        UiServerRoute.getContent(uiPath,"ui_theme_dark.css")))
    }
  }
}
