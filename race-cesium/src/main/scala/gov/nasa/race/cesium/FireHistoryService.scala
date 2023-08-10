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

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.UTF8JsonPullParser
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{DocumentRoute, FileServerRoute, ResponseData, WSContext}
import gov.nasa.race.ifSome
import gov.nasa.race.util.FileUtils
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.emptyConfig
import scalatags.Text

import java.io.File
import java.net.InetSocketAddress
import scala.collection.immutable.HashMap

/** simple fire descriptions populated from json files in configured archive dir */
case class FireSummary(id: String, name: String, year: Int, file: File )

class FireSummaryParser () extends UTF8JsonPullParser {
  val FIRE_SUMMARY = asc("fireSummary")
  val NAME = asc("name")
  val UNIQUE_ID = asc("uniqueId")
  val YEAR = asc("year")

  def parse (file: File): Option[FireSummary] = {
    var name: String = ""
    var uniqueId = ""
    var year = 0

    val returnIfComplete: (=>Any) => Any = (f) => { f; if (name.nonEmpty && uniqueId.nonEmpty && year > 0) return Some(FireSummary(uniqueId,name,year,file)) }

    FileUtils.fileContentsAsBytes(file) match {
      case Some(data) =>
        if (initialize(data)) {
          ensureNextIsObjectStart()
          readNextObjectMember(FIRE_SUMMARY) {
            foreachMemberInCurrentObject {
              case NAME =>
                returnIfComplete {name = quotedValue.toString}
              case UNIQUE_ID =>
                returnIfComplete {uniqueId = quotedValue.toString}
              case YEAR =>
                returnIfComplete {year = unQuotedValue.toInt}
              case _ => // ignore
            }
            return None // didn't find the required members
          }
        } else None
      case None => None
    }
  }
}

class FirePerimeterRendering (conf: Config) {
  val strokeWidth = conf.getDoubleOrElse("stroke-width", 2)
  val strokeColor = conf.getStringOrElse( "stroke-color", "orange")
  val fillColor = conf.getStringOrElse( "fill-color", "#f00000")
  val fillOpacity = conf.getDoubleOrElse("fill-opacity", 0.7)
  val dimFactor = conf.getDoubleOrElse("dim-factor", 0.8)

  def toJs: String = s"{ strokeColor: Cesium.Color.fromCssColorString('${strokeColor}'), strokeWidth: ${strokeWidth}, fillColor: Cesium.Color.fromCssColorString('${fillColor}'), fillOpacity: $fillOpacity, dimFactor: $dimFactor  }"
}

object FireHistoryService {
  val jsModule = "ui_cesium_firehistory.js"
  val icon = "firehistory-icon.svg"
}
import FireHistoryService._


/**
 * a service that displays historical fire data
 */
trait FireHistoryService extends CesiumService with FileServerRoute {

  val perimeterRender = new FirePerimeterRendering(config.getConfigOrElse("firehistory.perimeter-render", emptyConfig))
  val fireHistoryDir: File = config.getExistingDir("firehistory.directory")
  val fireHistories: HashMap[String,FireSummary] = loadFireHistories()

  def loadFireHistories(): HashMap[String,FireSummary] = {
    val parser = new FireSummaryParser()

    FileUtils.getMatchingFilesIn(fireHistoryDir, "**/*-summary.json").foldLeft(HashMap.empty[String,FireSummary]) { (map, file) =>
      parser.parse(file) match {
        case Some(fs) => map + (fs.id -> fs)
        case None => map
      }
    }
  }

  //--- data management

  def getFirePerimeterFile (fireId: String, dtg: String): Option[File] = {
    None
  }

  //--- route
  override def route: Route = uiCesiumFireHistoryRoute ~ super.route

  def uiCesiumFireHistoryRoute: Route = {
    get {
      pathPrefix("firehistory-data" ~ Slash) {
        extractUnmatchedPath { p =>
          FileUtils.fileContentsAsBytes(new File(fireHistoryDir, p.toString())) match {
            case Some(data) => complete(ResponseData.forPathName(pathName, data))
            case None => complete(StatusCodes.NotFound, p.toString())
          }
        }
      }
    } ~ fileAssetPath(jsModule) ~ fileAssetPath(icon)
  }

  //--- document fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments :+ addJsModule(jsModule)

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + geoFireHistoryConfig(requestUri,remoteAddr)

  def geoFireHistoryConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("firehistory")

    s"""export const firehistory = {
   ${cesiumLayerConfig(cfg, "/overlay/firehistory", "static map overlays with historic fire data")},
   zoomHeight: ${cfg.getIntOrElse("zoom-height", 80000)},
   perimeterRender: ${perimeterRender.toJs}
 };"""
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeFireHistoryConnection(ctx,queue)
  }

  def initializeFireHistoryConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    fireHistories.foreach { e =>
      ifSome(FileUtils.fileContentsAsUTF8String(e._2.file)) { json =>
        pushTo(ctx.remoteAddress, queue, TextMessage.Strict(json))
      }
    }
  }
}

class FireHistoryApp (val parent: ParentActor, val config: Config) extends DocumentRoute
  with FireHistoryService with ImageryLayerService with GeoLayerService with CesiumBldgRoute
