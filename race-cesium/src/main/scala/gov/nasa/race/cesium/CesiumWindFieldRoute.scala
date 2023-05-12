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
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.earth.WindFieldAvailable
import gov.nasa.race.http._
import gov.nasa.race.ui._
import gov.nasa.race.util.StringUtils
import scalatags.Text

import java.net.InetSocketAddress
import scala.collection.mutable

class DefaultVectorRendering (conf: Config) {
  val pointSize = conf.getDoubleOrElse("point-size", 3.0)
  val width = conf.getDoubleOrElse("stroke-width", 1.5)
  val color = conf.getStringOrElse( "color", "green")

  def toJsObject = s"""{ pointSize: $pointSize, strokeWidth: $width, color: Cesium.Color.fromCssColorString('$color') }"""
}

class DefaultAnimRendering (conf: Config) {
  val particlesTextureSize = conf.getIntOrElse("particles-texture-size", 64) // power of 2
  val maxParticles = particlesTextureSize * particlesTextureSize;
  val particleHeight = conf.getDoubleOrElse("particle-Height", 0.0)
  val lineWidth = conf.getDoubleOrElse("line-width", 1.5)
  val speedFactor = conf.getDoubleOrElse("speed-factor", 0.2)
  val fadeOpacity = conf.getDoubleOrElse("fade-opacity", 0.99)
  val dropRate = conf.getDoubleOrElse("drop-rate", 0.002)
  val dropRateBump = conf.getDoubleOrElse("drop-rate-bump", 0.01)

  val color = conf.getStringOrElse( "color", "cyan")

  def toJsObject = s"""{ particlesTextureSize: $particlesTextureSize, maxParticles: $maxParticles, lineWidth: $lineWidth, color: Cesium.Color.fromCssColorString('$color'), speedFactor: $speedFactor, particleHeight: $particleHeight, fadeOpacity: $fadeOpacity, dropRate: $dropRate, dropRateBump: $dropRateBump }"""
}

class DefaultContourRendering (conf: Config) {
  val strokeWidth = conf.getDoubleOrElse("stroke-width", 1.5)
  val strokeColor = conf.getStringOrElse( "stroke-color", "red")
  val fillColors = conf.getNonEmptyStringsOrElse( "fill-color", Array( "#f0000010", "#f0000040", "#f0000080", "#f00000c0", "#f00000f0"))

  def jsFillColors: String = {
    StringUtils.mkString( fillColors, "[",",","]")( clr=> s"Cesium.Color.fromCssColorString('$clr')")
  }

  def toJsObject = s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), fillColors:$jsFillColors }"""
}

/**
 * a CesiumRoute that displays wind fields
 *
 * this is strongly based on https://github.com/RaymanNg/3D-Wind-Field, using shader resources to compute and animate
 * particles that are put in a wind field that is derived from a client-side NetCDF structure.
 * See also https://cesium.com/blog/2019/04/29/gpu-powered-wind/
 *
 * Ultimately, this structure will be computed by this route and transmitted as a JSON object in order to remove
 * client side netcdfjs dependencies and offload client computation
 *
 * TODO - both windFieldEntries and urlMap still need re-org
 */
trait CesiumWindFieldRoute extends CesiumRoute with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient {

  case class WindField (wfa: WindFieldAvailable) {
    // this serves as the resource name and also a unique key (note we don't include baseDate as newer entries should take precedence)
    val urlName = f"${wfa.area}-${wfa.wfSource}-${wfa.wfType}-${wfa.forecastDate.format_yyyyMMdd}-t${wfa.forecastDate.getHour}%02dz"
    val json = wfa.toJsonWithUrl(s"wind-data/$urlName")
  }

  protected val windFields: mutable.LinkedHashMap[String,WindField] = mutable.LinkedHashMap.empty // urlName -> WindField

  //--- obtaining and updating wind fields

  override def receiveData: Receive = receiveWindFieldData orElse super.receiveData

  def receiveWindFieldData: Receive = {
    case BusEvent(_,wfa:WindFieldAvailable,_) =>
      val wf = WindField(wfa)
      addWindField(wf)
      push( TextMessage(wf.json))
  }

  // WATCH OUT - these can be used concurrently so we have to sync
  def addWindField(wf: WindField): Unit = synchronized { windFields += (wf.urlName -> wf) }
  def currentWindFieldValues: Seq[WindField] = synchronized { windFields.values.toSeq }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeWindConnection(ctx,queue)
  }

  def initializeWindConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = synchronized {
    val remoteAddr = ctx.remoteAddress
    currentWindFieldValues.foreach( wf=> pushTo(remoteAddr, queue, TextMessage(wf.json)))
  }

  //--- routes

  def windRoute: Route = {
    get {
        pathPrefix("wind-data" ~ Slash) {
          extractUnmatchedPath { p =>
            val pathName = p.toString()
            windFields.get(pathName) match {
              case Some(wf) => completeWithFileContent(wf.wfa.file)
              case None => complete(StatusCodes.NotFound, pathName)
            }
          }
        } ~
        pathPrefix("wind-particles" ~ Slash) { // this is the client side shader code, not the dynamic wind data
          extractUnmatchedPath { p =>
            val pathName = s"wind-particles/$p"
            complete( ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
          }
        } ~
        fileAssetPath("ui_cesium_wind.js") ~
        fileAssetPath("wind-icon.svg")
    }
  }

  override def route: Route = windRoute ~ super.route

  //--- document content (generated by js module)

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("wind-particles/windUtils.js"),
    extModule("wind-particles/particleSystem.js"),
    extModule("wind-particles/particlesComputing.js"),
    extModule("wind-particles/particlesRendering.js"),
    extModule("wind-particles/customPrimitive.js"),
    extModule("ui_cesium_wind.js")
  )

  //--- client config
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + windFieldConfig(requestUri,remoteAddr)

  def windFieldConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("windlayer")
    val defaultVectorRendering = new DefaultVectorRendering(cfg.getConfigOrElse("vector.render", NoConfig))
    val defaultAnimRendering = new DefaultAnimRendering(cfg.getConfigOrElse("anim.render", NoConfig))
    val defaultContourRendering = new DefaultContourRendering(cfg.getConfigOrElse("contour.render", NoConfig))

    s"""
export const windlayer = {
  vectorRender: ${defaultVectorRendering.toJsObject},
  animRender: ${defaultAnimRendering.toJsObject},
  contourRender: ${defaultContourRendering.toJsObject}
};"""
  }
}

/**
  * a single page application that processes wind channels
  */
class CesiumWindFieldApp(val parent: ParentActor, val config: Config) extends DocumentRoute with CesiumWindFieldRoute with ImageryLayerService