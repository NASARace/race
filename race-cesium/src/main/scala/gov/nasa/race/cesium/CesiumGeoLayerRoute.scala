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

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.SourceQueueWithComplete
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{DocumentRoute, ResponseData, WSContext}
import gov.nasa.race.ifSome
import gov.nasa.race.ui.{extModule, uiIcon, uiList, uiPanel, uiWindow}
import gov.nasa.race.util.{FileUtils, NetUtils}
import scalatags.Text

import java.io.File
import java.net.InetSocketAddress
import scala.collection.mutable

/**
 * user defined (partial) rendering parameters for GeoJSON content
 * (this is only used to create config.js sources - we don't need to resolve to values, hence we don't need to link
 * to the default rendering object)
 *
 * note this has to match Cesium.GeoDataSource ctor options
 */
class GeoJsonRendering (conf: Config) extends JsonSerializable {
  def stroke: Option[String] = conf.getOptionalString("stroke-color")
  def strokeWidth: Option[Double] = conf.getOptionalDouble("stroke-width")
  def markerColor: Option[String] = conf.getOptionalString( "marker-color")
  def markerSymbol: Option[String] = conf.getOptionalString("marker-symbol")
  def markerSize: Option[Int] = conf.getOptionalInt(("marker-size"))
  def fill: Option[String] = conf.getOptionalString("fill-color")

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    ifSome (stroke) { writer.writeStringMember("stroke", _) }
    ifSome (strokeWidth) { writer.writeDoubleMember("strokeWidth", _) }
    ifSome (markerColor) { writer.writeStringMember("markerColor", _) }
    ifSome (markerSymbol) { writer.writeStringMember("markerSymbol", _) }
    ifSome (markerSize) { writer.writeIntMember("markerSize", _) }
    ifSome (fill) { writer.writeStringMember("fill", _) }
  }

  def toJson: String = {
    val sb = new StringBuffer()
    sb.append('{')
    ifSome (stroke){ v=> sb.append(s"stroke:'$v',")}
    ifSome (strokeWidth) { v=> sb.append(s"strokeWidth:$v,") }
    ifSome (markerColor) { v=> sb.append(s"markerColor:'$v',") }
    ifSome (markerSymbol) { v=> sb.append(s"markerSymbol:'$v',") }
    ifSome (markerSize) { v=> sb.append(s"markerSize:$v,") }
    ifSome (fill) { v=> sb.append(s"fill:'$v'") }
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
}

object GeoLayer {
  def apply (conf: Config): GeoLayer = {
    val pathName = conf.getString( "path-name") // symbolic
    val file = new File(conf.getString("file"))
    val info = conf.getString("info")
    val renderCfg = conf.getOptionalConfig("render").map( new GeoJsonRendering(_))
    GeoLayer(pathName, file, info, renderCfg)
  }
}

case class GeoLayer (pathName: String, file: File, info: String, render: Option[GeoJsonRendering]) extends JsonSerializable {
  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer.writeStringMember("pathName", pathName)
    // file is only on the server
    writer.writeStringMember("info", info)
    ifSome(render) { r=> writer.writeObjectMember("render")( r.serializeMembersTo) }
  }
}

object GeoLayerRoute {
  val jsModule = "ui_cesium_geolayer.js"
  val icon = "infrastructure.svg"

  val windowId = "geolayer"
}
import GeoLayerRoute._

/**
 * a route that serves configured GeoJSON content
 */
trait GeoLayerRoute extends CesiumRoute {
  val defaultRendering = new DefaultGeoJsonRendering(config)
  val sources = mutable.LinkedHashMap.from( config.getConfigSeq("geolayer.sources").map(GeoLayer(_)).map(l=> l.pathName -> l))

  private val writer: JsonWriter = new JsonWriter() // there are too many of them

  //--- route
  override def route: Route = uiCesiumGeoLayerRoute ~ super.route

  def uiCesiumGeoLayerRoute: Route = {
    get {
      pathPrefix("geolayer-data" ~ Slash) { // this is the client side shader code, not the dynamic wind data
        extractUnmatchedPath { p =>
          val pathName = NetUtils.decodeUri(p.toString())
          sources.get(pathName) match {
            case Some(geoLayer) =>
              FileUtils.fileContentsAsBytes(geoLayer.file) match {
                case Some(content) => complete( ResponseData.json(content))
                case None => complete(StatusCodes.InternalServerError, p.toString())
              }
            case None => complete(StatusCodes.NotFound, p.toString())
          }
        }
      } ~
      fileAsset(jsModule) ~
      fileAsset(icon)
    }
  }

  //--- document fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumGeoLayerResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiGeoLayerWindow(), uiGeoLayerIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + geoLayerConfig(requestUri,remoteAddr)

  def uiCesiumGeoLayerResources: Seq[Text.TypedTag[String]] = Seq( extModule(jsModule))

  def uiGeoLayerWindow(title: String="Geo Layers"): Text.TypedTag[String] = {
    uiWindow(title, windowId, icon)(
      cesiumLayerPanel(windowId, "main.toggleShowGeoLayer(event)"),
      uiPanel("sources", true)(
        uiList(s"$windowId.source.list", 8, "main.selectGeoLayerSource(event)")
      ),
      uiPanel("data", false)(),
      uiPanel("source parameters", false)()
    )
  }

  def uiGeoLayerIcon: Text.TypedTag[String] = uiIcon(icon, s"main.toggleWindow(event,'$windowId')", "geolayer_icon")

  def geoLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("geolayer")
    s"""export const geolayer = {
  ${cesiumLayerConfig(cfg, "/infra/hifld", "infrastructure sub-layers from HIFLD")},
   render: ${defaultRendering.toJson}
 };"""
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeGeoLayerConnection(ctx,queue)
  }

  def initializeGeoLayerConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val msg = serializeSources( sources.values)
    pushTo( ctx.remoteAddress, queue, TextMessage.Strict(msg))
  }

  def serializeSources (srcs: Iterable[GeoLayer]): String = {
    writer.clear().writeObject(w=> w.writeArrayMember("geoLayers")(w=> srcs.foreach( _.serializeTo(w)))).toJson
  }
}


class CesiumGeoLayerApp (val parent: ParentActor, val config: Config) extends DocumentRoute with GeoLayerRoute