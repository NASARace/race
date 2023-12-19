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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.{JsonProducer, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.earth.SmokeAvailable
import gov.nasa.race.http._
import gov.nasa.race.ui._
import gov.nasa.race.util.StringUtils
import scalatags.Text

import java.net.InetSocketAddress
import java.io.File
import scala.collection.mutable

// define default contour rendering settings

class DefaultContourRenderingS (conf: Config) {
  val strokeWidth = conf.getDoubleOrElse("stroke-width", 1.5)
  val strokeColor = conf.getStringOrElse( "stroke-color", "grey")
  val smokeColor = conf.getStringOrElse( "smoke-color", "black")
  val cloudColor = conf.getStringOrElse( "cloud-color", "white")
  val alpha = conf.getDoubleOrElse("alpha", 0.5)

  def toJsObject = s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), smokeColor:Cesium.Color.fromCssColorString('$smokeColor'), cloudColor:Cesium.Color.fromCssColorString('$cloudColor'), alpha:$alpha }"""//s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), fillColors:$jsFillColors, alpha:$alpha }"""
}

object SmokeLayerService {
  val jsModule = "ui_cesium_smoke.js"
  val icon = "smoke-icon.svg"
}

import SmokeLayerService._


trait SmokeLayerService extends CesiumService with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient with JsonProducer{
  // service definition
  case class Layer (sla: SmokeAvailable, scType: String) {
    // case class that takes in an available object and the type - used to push data through the route server
    val urlName = f"$scType-${sla.satellite}-${sla.date.format_yMd_Hms_z}" // url where the file will be available
    val json = sla.toJsonWithUrl(s"smoke-data/$urlName")
    var file: Option[File] = None
    if (scType == "smoke") { // loads different file from smoke available depending on the type
      file = Some(sla.smokeFile)
    }
    if (scType == "cloud") {
      file = Some(sla.cloudFile)
    }
  }

  case class SmokeCloudLayer(sla: SmokeAvailable) {
    // case class that takes in an available object - used to push data through the websocket
    val smokeUrlName = f"smoke-${sla.satellite}-${sla.date.format_yMd_Hms_z}"
    val cloudUrlName = f"cloud-${sla.satellite}-${sla.date.format_yMd_Hms_z}"
    val uniqueId = f"${sla.satellite}-${sla.date.format_yMd_Hms_z}" // used to distinctly identify data
    val json = sla.toJsonWithTwoUrls(s"smoke-data/$smokeUrlName", s"smoke-data/$cloudUrlName", uniqueId)
  }

  protected val layers: mutable.LinkedHashMap[String,Layer] = mutable.LinkedHashMap.empty // urlName -> Layer
  protected val smokeCloudLayers: mutable.LinkedHashMap[String,SmokeCloudLayer] = mutable.LinkedHashMap.empty // urlName -> SmokecloudLayer

  //--- obtaining and updating smoke fields

  override def receiveData: Receive = receiveSmokeData orElse super.receiveData

  def receiveSmokeData: Receive = { // action for recieving bus message with new data
    case BusEvent(_,sla:SmokeAvailable,_) =>
      // create layers
      val cloudL = Layer(sla, "cloud") // create the cloud layer
      val smokeL = Layer(sla, "smoke") // create the smoke layer
      val smokeCloudSl = SmokeCloudLayer(sla) // create the smoke and cloud layer
      // add layers
      addLayer(cloudL)
      addLayer(smokeL)
      addSmokeCloudLayer(smokeCloudSl)
      // push to route server
      push( TextMessage(smokeCloudSl.json))
  }

  // add new layer functions
  // WATCH OUT - these can be used concurrently so we have to sync
  def addLayer(sl: Layer): Unit = synchronized { layers += (sl.urlName -> sl) }
  def currentSmokeLayerValues: Seq[Layer] = synchronized { layers.values.toSeq }
  // WATCH OUT - these can be used concurrently so we have to sync
  def addSmokeCloudLayer(sl: SmokeCloudLayer): Unit = synchronized { smokeCloudLayers += (sl.uniqueId -> sl) }
  def currentSmokeCloudLayerValues: Seq[SmokeCloudLayer] = synchronized { smokeCloudLayers.values.toSeq }

  //--- route
  def smokeRoute: Route = {
    get {
      pathPrefix("smoke-data" ~ Slash) {
        extractUnmatchedPath { p =>
          val pathName = p.toString()
          layers.get(pathName) match { //serves the file to the url
            case Some(sl) => {
              // serves the layers file to its url - this is done for both smoke and cloud layers separately
              completeWithFileContent(sl.file.get)
            }
            case None => complete(StatusCodes.NotFound, pathName)
          }
        }
      } ~
        pathPrefix("smoke-cloud" ~ Slash) { // this is the client side shader code - used for client requests to the url
          extractUnmatchedPath { p =>
            val pathName = s"smoke-cloud/$p"
            complete( ResponseData.forPathName(pathName, getFileAssetContent(pathName))) // sends file contents to the client
          }
        } ~
        fileAssetPath(jsModule) ~ // serves the js module and icon to make available to the client
        fileAssetPath(icon)
    }
  }

  override def route: Route = smokeRoute ~ super.route

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeSmokeConnection(ctx,queue)
  }

  def initializeSmokeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = synchronized {
    // adds smoke and cloud layer objects to the websocket as messages
    val remoteAddr = ctx.remoteAddress
    currentSmokeCloudLayerValues.foreach( sl => pushTo(remoteAddr, queue, TextMessage(sl.json))) // pushes object to the UI
  }

  //--- document content generated by js module

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("ui_cesium_smoke.js")
  )

  //--- client config
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + smokeLayerConfig(requestUri,remoteAddr)

  def smokeLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    // defines the config sent to the js module
    val cfg = config.getConfig("smokelayer")
    val defaultContourRenderingS = new DefaultContourRenderingS(cfg.getConfigOrElse("contour.render", NoConfig))

    s"""
    export const smokelayer = {
      contourRender: ${defaultContourRenderingS.toJsObject},
      followLatest: ${cfg.getBooleanOrElse("follow-latest", false)}
    };"""
  }
}

/**
  * a single page application that processes smoke and cloud segmentation images
  */
class CesiumSmokeApp(val parent: ParentActor, val config: Config) extends DocumentRoute with SmokeLayerService with ImageryLayerService

class CesiumSmokeDemoApp(val parent: ParentActor, val config: Config) //custom smoke demo app to show goes-r hotspots and smoke
  extends DocumentRoute
    with ImageryLayerService
    with GoesrService
    with SmokeLayerService