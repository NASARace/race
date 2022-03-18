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

import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{CachedFileAssetMap, FSCachedProxyRoute, HttpServer, MainDocumentRoute, ProxyRaceRoute, PushWSRaceRoute, QueryProxyRoute, ResponseData, WSContext}
import gov.nasa.race.ui.{extModule, extScript, uiCheckBox, uiChoice, uiColumnContainer, uiIcon, uiList, uiPanel, uiRowContainer, uiSlider, uiWindow}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import scalatags.Text

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.SeqMap


object CesiumWindRoute extends CachedFileAssetMap {
  val sourcePath = "./race-cesium/src/main/resources/gov/nasa/race/cesium"
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
  */
trait CesiumWindRoute extends QueryProxyRoute with FSCachedProxyRoute with CesiumRoute with PushWSRaceRoute {

  //--- init wind fields

  val windDir: String = config.getString("wind-dir")

  val windFields: mutable.SeqMap[String,CesiumLayer] = config.getConfigSeq("wind-fields").foldLeft(SeqMap.empty[String,CesiumLayer]) { (map, layerConf) =>
    val name = layerConf.getString("name")
    val url = layerConf.getString("url")
    val file = getFileFromRequestUri(url)
    val show = layerConf.getBooleanOrElse("show", false)
    map += name -> CesiumLayer(name,url,file,show)
  }

  override def onRaceStarted(server: HttpServer): Boolean = {
    windFields.values.foreach { wf =>
      getFileFromRequestUri(wf.url) match {
        case Some(file) =>
          fetchFile(file, wf.url){
            push(windFieldMessage(wf))
          }
        case None => // ignore
      }
    }
    super.onRaceStarted(server)
  }

  def windFieldMessage(layer: CesiumLayer): TextMessage.Strict = {
    //val msg = s"""{"windField":{"name":"${layer.name}","date":${layer.date.toEpochMillis},"url":"${layer.url}","show":${layer.show}}}"""
    val msg = s"""{"windField":{"name":"${layer.name}","date":${DateTime.now.toEpochMillis},"url":"${layer.url}","show":${layer.show}}}"""
    TextMessage.Strict(msg)
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeWindConnection(ctx,queue)
  }

  def initializeWindConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.remoteAddress
    windFields.values.foreach{ layer=>
      pushTo( remoteAddr, queue, windFieldMessage(layer))
    }
  }

  //--- routes

  def windRoute: Route = {
    get {
      path("ui_cesium_wind.js") {
        complete( ResponseData.js( CesiumWindRoute.getContent("ui_cesium_wind.js")))
      } ~ path("wind-icon.svg") {
        complete( ResponseData.svg( CesiumWindRoute.getContent("wind-icon.svg")))
      } ~ pathPrefix("wind-data") {
        extractUnmatchedPath { p =>
          val file = new File(s"$windDir/$p")
          FileUtils.fileContentsAsBytes(file) match {
            case Some(content) => complete( ResponseData.bytes(content))
            case None => complete(StatusCodes.NotFound, p.toString())
          }
        }
      } ~ pathPrefix("wind") {
        extractUnmatchedPath { p =>
          val pathName = s"wind/$p"
          if (pathName.endsWith(".js")) {
            complete( ResponseData.js( CesiumWindRoute.getContent(pathName)))
          } else if (pathName.endsWith(".frag") || pathName.endsWith(".vert")) {
            complete( ResponseData.glsl( CesiumWindRoute.getContent(pathName)))
          } else {
            complete(StatusCodes.NotFound, pathName)
          }
        }
      } ~ path("proxy") {  // TODO this is going away
         completeProxied
       }
    }
  }

  override def route: Route = windRoute ~ super.route

  //--- document content

  def uiWindWindow(title: String="Wind"): Text.TypedTag[String] = {
    uiWindow(title, "wind")(
      uiList("wind.list", 10, "main.selectWind(event)"),
      uiRowContainer()(
        uiCheckBox("show wind", "main.toggleWind(event)", "wind.show")
      ),
      uiPanel("display", false)(
        uiColumnContainer("align_right")(
          //uiChoice("max particles", "wind.max_particles", "main.windMaxParticlesChanged(event)"),
          uiSlider("max particles", "wind.max_particles", "main.windMaxParticlesChanged(event)"),
          uiSlider("height", "wind.height", "main.windHeightChanged(event)"),
          uiSlider("fade opacity", "wind.fade_opacity", "main.windFadeOpacityChanged(event)"),
          uiSlider("drop", "wind.drop", "main.windDropRateChanged(event)"),
          uiSlider("drop bump", "wind.drop_bump", "main.windDropRateBumpChanged(event)"),
          uiSlider("speed", "wind.speed", "main.windSpeedChanged(event)"),
          uiSlider("width", "wind.width", "main.windWidthChanged(event)")
        ),
      )
    )
  }
  def uiWindIcon: Text.TypedTag[String] = {
    uiIcon("wind-icon.svg", "main.toggleWindow(event,'wind')", "wind_icon")
  }

  def uiCesiumWindResources: Seq[Text.TypedTag[String]] = {
    Seq(
      extScript("proxy?https://www.lactame.com/lib/netcdfjs/0.7.0/netcdfjs.min.js"), // TODO - move to server

      extModule("wind/windUtils.js"),
      extModule("wind/particleSystem.js"),
      extModule("wind/particlesComputing.js"),
      extModule("wind/particlesRendering.js"),
      extModule("wind/customPrimitive.js"),

      extModule("ui_cesium_wind.js")
    )
  }

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumWindResources

  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiWindWindow(), uiWindIcon)

  //--- client config
}

object CesiumWindApp extends CachedFileAssetMap {
  val sourcePath = "./race-cesium/src/main/resources/gov/nasa/race/cesium"
}

/**
  * a single page application that processes track channels
  */
class CesiumWindApp (val parent: ParentActor, val config: Config) extends MainDocumentRoute with CesiumWindRoute {
  val mainModule = "main_wind.js"
  val mainCss = "main_wind.css"

  override def mainModuleContent: Array[Byte] = CesiumWindApp.getContent(mainModule)
  override def mainCssContent: Array[Byte] = CesiumWindApp.getContent(mainCss)
}