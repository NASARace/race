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

import akka.http.javadsl.model.headers.ContentEncoding
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncoding
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpHeader, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.util.ByteString
import gov.nasa.race.cesium.GeoLayer.MODULE_PREFIX
import gov.nasa.race.common.{JsonProducer, JsonSerializable, JsonWriter}
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{DocumentRoute, FileServerRoute, ResponseData, WSContext}
import gov.nasa.race.ifSome
import gov.nasa.race.ui.{extModule, uiIcon, uiKvTable, uiList, uiPanel, uiTreeList, uiWindow}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.{FileUtils, NetUtils}
import scalatags.Text

import java.io.{File, FileInputStream, InputStream}
import java.net.InetSocketAddress
import java.util.zip.GZIPInputStream
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object GeoLayer {
  def apply (conf: Config): GeoLayer = {
    val pathName = conf.getString( "pathname") // symbolic
    val file = new File(conf.getString("file"))
    val date = conf.getDateTimeOrElse("date", DateTime.ofEpochMillis(file.lastModified()))
    val info = conf.getString("info")
    val renderCfg = conf.getOptionalConfig("render").map( new GeoJsonRendering(_))
    GeoLayer(pathName, file, date, info, renderCfg)
  }

  val MODULE_PREFIX = "geolayer-module"
}

/**
 * user defined (partial) rendering parameters for GeoJSON content
 * (this is only used to create config.js sources - we don't need to resolve to values, hence we don't need to link
 * to the default rendering object)
 *
 * note this has to match Cesium.GeoDataSource ctor options
 */
class GeoJsonRendering (conf: Config) extends JsonSerializable {
  //--- standard Cesium GeoJsonDataSource attributes
  def stroke: Option[String] = conf.getOptionalString("stroke-color")
  def strokeWidth: Option[Double] = conf.getOptionalDouble("stroke-width")
  def markerColor: Option[String] = conf.getOptionalString( "marker-color")
  def markerSymbol: Option[String] = conf.getOptionalString("marker-symbol")
  def markerSize: Option[Int] = conf.getOptionalInt(("marker-size"))
  def fill: Option[String] = conf.getOptionalString("fill-color")

  //--- our extended attributes
  def module: Option[String] = conf.getOptionalString("module") // used on client side for specific rendering and property mod
  def pointDistance: Option[Int] = conf.getOptionalInt("point-dist")
  def geometryDistance: Option[Int] = conf.getOptionalInt("geometry-dist")

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    ifSome (stroke) { writer.writeStringMember("stroke", _) }
    ifSome (strokeWidth) { writer.writeDoubleMember("strokeWidth", _) }
    ifSome (markerColor) { writer.writeStringMember("markerColor", _) }
    ifSome (markerSymbol) { writer.writeStringMember("markerSymbol", _) }
    ifSome (markerSize) { writer.writeIntMember("markerSize", _) }
    ifSome (fill) { writer.writeStringMember("fill", _) }
    ifSome (module) { mod=> writer.writeStringMember("module", s"./${GeoLayer.MODULE_PREFIX}/$mod") }
    ifSome (pointDistance) { writer.writeIntMember("pointDistance", _) }
    ifSome (geometryDistance) { writer.writeIntMember("geometryDistance", _) }

  }

  def toJs: String = {
    val sb = new StringBuffer()
    sb.append('{')
    ifSome (stroke){ v=> sb.append(s"stroke:'$v',")}
    ifSome (strokeWidth) { v=> sb.append(s"strokeWidth:$v,") }
    ifSome (markerColor) { v=> sb.append(s"markerColor:'$v',") }
    ifSome (markerSymbol) { v=> sb.append(s"markerSymbol:'$v',") }
    ifSome (markerSize) { v=> sb.append(s"markerSize:$v,") }
    ifSome (fill) { v=> sb.append(s"fill:'$v',") }
    ifSome (module) { v=> sb.append(s"module:'./${GeoLayer.MODULE_PREFIX}/$v',") }
    ifSome (pointDistance) { v=> sb.append(s"pointDistance:$v,") }
    ifSome (geometryDistance) { v=> sb.append(s"geometryDistance:$v,") }
    sb.append('}')
    sb.toString
  }
}

class DefaultGeoJsonRendering (conf: Config) extends GeoJsonRendering(conf) {
  override def stroke: Option[String] = Some(conf.getStringOrElse("stroke-color", "#FF1493"))
  override def strokeWidth: Option[Double] = Some(conf.getDoubleOrElse("stroke-width", 2))
  override def markerColor: Option[String] = Some(conf.getStringOrElse( "marker-color", "#FF1493"))
  override def markerSymbol: Option[String] = Some(conf.getStringOrElse("marker-symbol", "square"))
  override def markerSize: Option[Int] = Some(conf.getIntOrElse("marker-size", 32))
  override def fill: Option[String] = Some(conf.getStringOrElse("fill-color","#FF69B4"))

  // we don't provide default values for extended rendering attributes
}

case class GeoLayer (pathName: String, file: File, date: DateTime, info: String, render: Option[GeoJsonRendering]) extends JsonSerializable {
  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer.writeStringMember("pathName", pathName)
    // file is only on the server
    writer.writeDateTimeMember("date", date)
    writer.writeStringMember("info", info)
    ifSome(render) { r=> writer.writeObjectMember("render")( r.serializeMembersTo) }
  }
}

object GeoLayerRoute {
  val jsModule = "ui_cesium_geolayer.js"
  val icon = "geomarker-icon.svg"

  val windowId = "geolayer"
}
import GeoLayerRoute._

/**
 * a route that serves configured GeoJSON content
 */
trait GeoLayerRoute extends CesiumRoute with FileServerRoute with JsonProducer {
  private val defaultRendering = new DefaultGeoJsonRendering(config.getConfigOrElse("geolayer.render", NoConfig))
  private val sources = mutable.LinkedHashMap.from( config.getConfigSeq("geolayer.sources").map(GeoLayer(_)).map(l=> l.pathName -> l))
  private val renderModules = getRenderModules(sources.values)

  def getRenderModules(srcs: Iterable[GeoLayer]): Seq[Text.TypedTag[String]] = {
    val mods = ArrayBuffer.empty[Text.TypedTag[String]]
    ifSome(defaultRendering.module){ mod=> mods += extModule(s"$MODULE_PREFIX/$mod") }
    srcs.foreach { src=>
      for (
        render <- src.render;
        mod <- render.module
      ) mods += extModule(s"$MODULE_PREFIX/$mod")
    }
    mods.toSeq
  }

  //--- route
  override def route: Route = uiCesiumGeoLayerRoute ~ super.route

  def uiCesiumGeoLayerRoute: Route = {
    get {
      pathPrefix("geolayer-data" ~ Slash) {
        extractUnmatchedPath { p =>
          val pathName = NetUtils.decodeUri(p.toString())
          sources.get(pathName) match {
            case Some(geoLayer) =>
              completeWithFileContent( geoLayer.file)
            case None => complete(StatusCodes.NotFound, pathName)
          }
        }
      } ~
      pathPrefix( MODULE_PREFIX ~ Slash) {
        extractUnmatchedPath { p =>
          completeWithFileAsset(p, s"$MODULE_PREFIX/$p")
        }
      } ~
        pathPrefix( "geolayer-asset" ~ Slash) {
          extractUnmatchedPath { p =>
            completeWithFileAsset(p, p.toString)
          }
        } ~
      fileAssetPath(jsModule) ~
      fileAssetPath(icon)
    }
  }

  //--- document fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumGeoLayerResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiGeoLayerWindow(), uiGeoLayerIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + geoLayerConfig(requestUri,remoteAddr)

  def uiCesiumGeoLayerResources: Seq[Text.TypedTag[String]] =  renderModules :+ extModule(jsModule)

  def uiGeoLayerWindow(title: String="Geo Layers"): Text.TypedTag[String] = {
    uiWindow(title, windowId, icon)(
      cesiumLayerPanel(windowId, "main.toggleShowGeoLayer(event)"),
      uiPanel("sources", true)(
        uiTreeList(s"$windowId.source.list", maxRows = 15, minWidthInRem = 25, selectAction="main.selectGeoLayerSource(event)")
      ),
      uiPanel("data", false)(
        uiKvTable(s"$windowId.object", maxRows = 15, maxWidthInRem = 20, minWidthInRem = 25)
      ),
      uiPanel("source parameters", false)()
    )
  }

  def uiGeoLayerIcon: Text.TypedTag[String] = uiIcon(icon, s"main.toggleWindow(event,'$windowId')", "geolayer_icon")

  def geoLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("geolayer")
    s"""export const geolayer = {
   ${cesiumLayerConfig(cfg, "/overlay/annotation", "static map overlays with symbolic data")},
   render: ${defaultRendering.toJs}
 };"""
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeGeoLayerConnection(ctx,queue)
  }

  def initializeGeoLayerConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    pushTo( ctx.remoteAddress, queue, TextMessage.Strict( toNewJson( serializeSources(_, sources.values))))
  }

  def serializeSources (writer: JsonWriter, srcs: Iterable[GeoLayer]): Unit = {
    writer
      .beginObject
      .writeArrayMember("geoLayers")(w=> srcs.foreach( _.serializeTo(w)))
      .endObject
  }
}


class CesiumGeoLayerApp (val parent: ParentActor, val config: Config) extends DocumentRoute with GeoLayerRoute
class CesiumGeoImgApp (val parent: ParentActor, val config: Config) extends DocumentRoute with GeoLayerRoute with ImageryLayerRoute