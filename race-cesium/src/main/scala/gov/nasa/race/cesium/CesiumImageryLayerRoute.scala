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

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.Config
import gov.nasa.race.cesium.GeoLayerRoute.{icon, jsModule, windowId}
import gov.nasa.race.common.{JsWritable, JsonProducer, JsonSerializable, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.DocumentRoute
import gov.nasa.race.ifSome
import gov.nasa.race.ui.{extModule, uiColumnContainer, uiIcon, uiKvTable, uiPanel, uiSlider, uiTreeList, uiWindow}
import gov.nasa.race.util.ClassLoaderUtils
import scalatags.Text

import java.net.InetSocketAddress
import scala.collection.mutable

/**
 * imagery instance specific rendering parameters
 */
class ImageryRendering (conf: Config) extends JsWritable {
  def alpha: Option[Double] = conf.getOptionalDouble("alpha")
  def brightness: Option[Double] = conf.getOptionalDouble("brightness")
  def contrast: Option[Double] = conf.getOptionalDouble("contrast")
  def hue: Option[Double] = conf.getOptionalDouble("hue")
  def saturation: Option[Double] = conf.getOptionalDouble("saturation")
  def gamma: Option[Double] = conf.getOptionalDouble("gamma")


  def appendJsMembers (sb: StringBuffer): Unit = {
    ifSome(alpha){ v=> appendDoubleMember(sb,"alpha",v) }
    ifSome(brightness){ v=> appendDoubleMember(sb,"brightness",v) }
    ifSome(contrast){ v=> appendDoubleMember(sb, "contrast",v) }
    ifSome(hue){ v=> appendDoubleMember(sb,"hue",v) }
    ifSome(saturation){ v=> appendDoubleMember(sb, "saturation",v) }
    ifSome(gamma){ v=> appendDoubleMember(sb, "gamma",v) }
  }
}

class DefaultImageryRendering (conf: Config) extends ImageryRendering(conf) {
  override def alpha: Option[Double] = Some(conf.getDoubleOrElse("alpha", 1.0))
  override def brightness: Option[Double] = Some(conf.getDoubleOrElse("brightness", 1.0))
  override def contrast: Option[Double] = Some(conf.getDoubleOrElse("contrast", 1.0))
  override def hue: Option[Double] = Some(conf.getDoubleOrElse("hue", 1.0))
  override def saturation: Option[Double] = Some(conf.getDoubleOrElse("saturation", 1.0))
  override def gamma: Option[Double] = Some(conf.getDoubleOrElse("gamma", 1.0))
}

object ImgLayer {
  def apply (conf: Config): ImgLayer = {
    val pathName = conf.getString( "pathname")
    val info = conf.getString("info")
    val provider: CesiumImageryProvider = ClassLoaderUtils.newInstance( this, conf.getString("provider-class"),
      Array(classOf[Config]), Array(conf)).get
    val renderCfg = conf.getOptionalConfig("render").map( new ImageryRendering(_))

    ImgLayer(pathName, info, provider, renderCfg)
  }
}

/**
 * server model of imagery spec
 */
case class ImgLayer (pathName: String, info: String, provider: CesiumImageryProvider, render: Option[ImageryRendering])  {

  def appendJs (sb: StringBuffer): Unit = {
    sb.append('{')
    sb.append(s"pathName:'$pathName',")
    sb.append(s"info:'$info',")

    ifSome(render){ r=> r.appendObjectMember(sb, "render", r) }
    sb.append('}')
  }

  def toJs: String = {
    val sb = new StringBuffer()

    sb.toString()
  }
}

object ImageryLayerRoute {
  val jsModule = "ui_cesium_imglayer.js"
  val icon = "imagery-icon.svg"
  val windowId = "imglayer"
}
import ImageryLayerRoute._

/**
 * a RaceRouteInfo for Cesium Imagery Layers
 */
trait ImageryLayerRoute extends CesiumRoute with JsonProducer {
  val defaultRendering = new DefaultImageryRendering(config.getConfigOrElse("imglayer.render", NoConfig))
  val sources = mutable.LinkedHashMap.from( config.getConfigSeq("imglayer.sources").map(ImgLayer(_)).map(l=> l.pathName -> l))

  //--- route
  override def route: Route = imgLayerRoute ~ super.route

  def imgLayerRoute: Route = ???

  //--- document fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiImgLayerResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiImgLayerWindow(), uiImgLayerIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + imgLayerConfig(requestUri,remoteAddr)

  def uiImgLayerResources: Seq[Text.TypedTag[String]] =  Seq( extModule(jsModule))

  def uiImgLayerWindow (title: String="Imagery Layers"): Text.TypedTag[String] = {
    uiWindow(title, windowId, icon)(
      cesiumLayerPanel(windowId, "main.toggleShowImgLayer(event)"),
      uiPanel("sources", true)(
        uiTreeList(s"$windowId.source.list", maxRows = 15, minWidthInRem = 25, selectAction="main.selectImgLayer(event)")
      ),
      uiPanel("layer parameters", false)(
        uiColumnContainer("align_right")(
          uiSlider("alpha", "view.img.alpha", "main.setImgAlpha(event)"),
          uiSlider("brightness", "view.img.brightness", "main.setImgBrightness(event)"),
          uiSlider("contrast", "view.img.contrast", "main.setImgContrast(event)"),
          uiSlider("hue", "view.img.hue", "main.setImgHue(event)"),
          uiSlider("saturation", "view.img.saturation","main.setImgSaturation(event)"),
          uiSlider("gamma", "view.img.gamma", "main.setImgGamma(event)")
        )
      )
    )
  }

  def uiImgLayerIcon: Text.TypedTag[String] = uiIcon(icon, s"main.toggleWindow(event,'$windowId')", "imglayer_icon")

  def imgLayerConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("imgLayer")
    s"""
 export const imgLayer = {
  ${cesiumLayerConfig(cfg, "/imagery", "static imagery")},
   sources: [

   ],
   render: ${defaultRendering.toJs}
 };"""
  }

    //--- no websocket messages (yet?)
}

//--- configurable cesium map service abstractions (TODO - needs more complete constructor options

trait CesiumImageryProvider {
  val config: Config

  // the js code to instantiate the Cesium ImageryLayer (part of the js module config)
  def instantiationCode: String
}

class DefaultImageryProvider (val config: Config) extends CesiumImageryProvider {
  def instantiationCode: String = "" // nothing (all Cesium built-in)

}

class ArcGisMapServerImageryProvider (val config: Config) extends CesiumImageryProvider {
  def instantiationCode: String = {
    s"""new Cesium.ArcGisMapServerImageryProvider({ url: '${config.getString("url")}' })"""
  }
}

class OpenStreetMapImageryProvider (val config: Config) extends CesiumImageryProvider {
  def instantiationCode: String =  {
    s"""new Cesium.Cesium.OpenStreetMapImageryProvider({ url:'${config.getString("url")}' })"""
  }
}

class WebMapServiceImageryProvider (val config: Config) extends CesiumImageryProvider {
  def instantiationCode: String = {
    val sb = new StringBuffer()
    sb.append(s"""new Cesium.WebMapServiceImageryProvider({\n""")
    sb.append(s"""    url:'${config.getString("url")}',\n""")
    sb.append("    enablePickFeatures: false,\n")
    ifSome(config.getOptionalString("layers")) { s=> sb.append(s"""    layers:'$s',\n""") }
    ifSome(config.getOptionalString("parameters")) { s=> sb.append(s"""    parameters:'$s',\n""") }
    sb.append("})")
    sb.toString
  }
}

class WebMapTileServiceImageryProvider (val config: Config) extends CesiumImageryProvider {
  def instantiationCode: String = {
    val sb = new StringBuffer()
    sb.append(s"""new Cesium.WebMapTileServiceImageryProvider({\n""")
    sb.append(s"""    url:'${config.getString("url")}',\n""")
    ifSome(config.getOptionalString("layer")) { s=> sb.append(s"""    layer:'$s',\n""") }
    ifSome(config.getOptionalString("style")) { s=> sb.append(s"""    style:'$s',\n""") }
    ifSome(config.getOptionalString("tileMatrixSetID")) { s=> sb.append(s"""    tileMatrixSetID:'$s',\n""") }
    ifSome(config.getOptionalInt("maximumLevel")) { n=> sb.append(s"""    maximumLevel:$n,\n""") }
    ifSome(config.getOptionalString("format")) { s=> sb.append(s"""    format:'$s',\n""") }
    sb.append("})")
    sb.toString
  }
}

class TileMapServiceImageryProvider (val config: Config) extends CesiumImageryProvider {
  def instantiationCode: String = {
    s"""new Cesium.TileMapServiceImageryProvider({ url:'${config.getString("url")}' })"""
  }
}

/**
 * single layer test application
 */
class CesiumImageryLayerApp (val parent: ParentActor, val config: Config) extends DocumentRoute with ImageryLayerRoute

