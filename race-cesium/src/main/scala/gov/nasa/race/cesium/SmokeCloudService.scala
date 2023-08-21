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
import scala.collection.mutable

// define default contours

class DefaultContourRenderingS (conf: Config) {
  val strokeWidth = conf.getDoubleOrElse("stroke-width", 1.5)
  val strokeColor = conf.getStringOrElse( "stroke-color", "grey")
  val fillColors = conf.getNonEmptyStringsOrElse( "fill-color", Array("#00000080", "#40404080", "#80808080", "#bfbfbf80", "#ffffff80"))//"e6e6e6", "808080", "404040"))//"#f0000010", "#f0000040", "#f0000080", "#f00000c0", "#f00000f0")) //update colors
  val alpha = conf.getDoubleOrElse("alpha", 0.5)

  def jsFillColors: String = {
    StringUtils.mkString( fillColors, "[",",","]")( clr=> s"Cesium.Color.fromCssColorString('$clr')")
  }

  def toJsObject = s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), fillColors:$jsFillColors, alpha:$alpha }"""
}

object SmokeLayerService {
  val jsModule = "ui_cesium_smoke.js"
  val icon = "smoke-icon.svg"
}
import SmokeLayerService._


trait SmokeLayerService extends CesiumService with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient with JsonProducer{
  case class SmokeLayer (sla: SmokeAvailable) {
    // this serves as the resource name and also a unique key (note we don't include baseDate as newer entries should take precedence)
    val urlName = f"${sla.scType}-${sla.satellite}-${sla.date.format_yMd_Hms_z}"
    val json = sla.toJsonWithUrl(s"smoke-data/$urlName")
  }

  protected val smokeLayers: mutable.LinkedHashMap[String,SmokeLayer] = mutable.LinkedHashMap.empty // urlName -> SmokeLayer

  //--- obtaining and updating smoke fields

  override def receiveData: Receive = receiveSmokeData orElse super.receiveData

  def receiveSmokeData: Receive = {
    case BusEvent(_,sla:SmokeAvailable,_) =>
      val sl = SmokeLayer(sla)
      addSmokeLayer(sl)
      push( TextMessage(sl.json))
  }

  // WATCH OUT - these can be used concurrently so we have to sync
  def addSmokeLayer(sl: SmokeLayer): Unit = synchronized { smokeLayers += (sl.urlName -> sl) }
  def currentSmokeLayerValues: Seq[SmokeLayer] = synchronized { smokeLayers.values.toSeq }

  //--- route
  def smokeRoute: Route = {
    get {
      pathPrefix("smoke-data" ~ Slash) {
        extractUnmatchedPath { p =>
          val pathName = p.toString()
          smokeLayers.get(pathName) match {
            case Some(sl) => completeWithFileContent(sl.sla.file) // we will have two files - one for smoke and one for cloud
            case None => complete(StatusCodes.NotFound, pathName)
          }
        }
      } ~
        pathPrefix("smoke-cloud" ~ Slash) { // this is the client side shader code, not the dynamic wind data
          extractUnmatchedPath { p =>
            val pathName = s"smoke-cloud/$p"
            complete( ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
          }
        } ~
        fileAssetPath(jsModule) ~
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
    val remoteAddr = ctx.remoteAddress
    currentSmokeLayerValues.foreach( sl => pushTo(remoteAddr, queue, TextMessage(sl.json)))
  }

  //--- document content generated by js module

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("ui_cesium_smoke.js")
  )

  //--- client config
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + smokeLayerConfig(requestUri,remoteAddr)

  def smokeLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("smokelayer")
    val defaultContourRenderingS = new DefaultContourRenderingS(cfg.getConfigOrElse("contour.render", NoConfig))

    s"""
export const smokelayer = {
  contourRender: ${defaultContourRenderingS.toJsObject},
  followLatest: ${cfg.getBooleanOrElse("follow-latest", true)}
};"""
  }
}

/**
  * a single page application that processes wind channels
  */
class CesiumSmokeApp(val parent: ParentActor, val config: Config) extends DocumentRoute with SmokeLayerService with ImageryLayerService