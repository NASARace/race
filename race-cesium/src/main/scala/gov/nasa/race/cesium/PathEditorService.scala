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

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.http.{DocumentRoute, FileServerRoute, ResponseData, WSContext}
import gov.nasa.race.uom.DateTime
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.ifSome
import gov.nasa.race.util.FileUtils
import scalatags.Text

import java.io.File
import java.net.InetSocketAddress
import scala.collection.mutable

/**
 * object representing a GeoJSON file with a single Feature and LineString geometry
 */
case class PathEntry(name: String, date: DateTime, info: String, file: File)

object PathEditorService {
  val jsModule = "ui_cesium_patheditor.js"
  val icon = "path-icon.svg"
}
import PathEditorService._

/**
 * base type for a service for client-based path editing functions.
 * This is an example of a service that uses client (browser) code to generate data
 *
 * Paths are stored and transmitted as GeoJSON Features with LineString geometries
 */
trait PathEditorService extends CesiumService with FileServerRoute {

  protected val paths: mutable.HashMap[String,PathEntry] = loadPaths
  val pathsDir: File = config.getExistingDir("paths.directory")

  //--- data management

  def loadPaths: mutable.HashMap[String,PathEntry] = {
    mutable.HashMap.empty
  }

  //--- route
  override def route: Route = uiCesiumPathRoute ~ super.route

  def uiCesiumPathRoute: Route = {
    get {
      pathPrefix("path-data" ~ Slash) {
        extractUnmatchedPath { p =>
          FileUtils.fileContentsAsBytes(new File(pathsDir, p.toString())) match {
            case Some(data) => complete(ResponseData.forPathName(pathName, data))
            case None => complete(StatusCodes.NotFound, p.toString())
          }
        }
      }
    } ~ fileAssetPath(jsModule) ~ fileAssetPath(icon)
  }

  //--- document fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments :+ addJsModule(jsModule)

  override def getConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri, remoteAddr) + getPathEditorConfig(requestUri, remoteAddr)

  def getPathEditorConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("patheditor")

    s"""export const patheditor = {
   ${cesiumLayerConfig(cfg, "/overlay/patheditor", "interactive editor for paths")},
 };"""
  }

  //--- websocket (push notification)

  protected override def initializeConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializePathEditorConnection(ctx, queue)
  }

  def initializePathEditorConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    paths.foreach { e =>
      ifSome(FileUtils.fileContentsAsUTF8String(e._2.file)) { json =>
        pushTo(ctx.remoteAddress, queue, TextMessage.Strict(json))
      }
    }
  }
}

class PathEditorApp(val parent: ParentActor, val config: Config) extends DocumentRoute
  with PathEditorService with ImageryLayerService with GeoLayerService with CesiumBldgRoute
