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
import akka.http.scaladsl.server.{PathMatcher, Route}
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.Config
import gov.nasa.race.common.{JsConvertible, JsonProducer, JsonSerializable, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{DocumentRoute, ResponseData}
import gov.nasa.race.ifSome
import gov.nasa.race.ui.{UiSettingsRoute, extModule, uiColumnContainer, uiIcon, uiKvTable, uiLabel, uiList, uiPanel, uiRowContainer, uiSlider, uiText, uiTreeList, uiWindow}
import gov.nasa.race.util.{ClassLoaderUtils, FileUtils, StringUtils}
import scalatags.Text

import java.io.File
import java.net.InetSocketAddress
import scala.collection.mutable

/**
 * imagery instance specific rendering parameters
 */
class ImageryRendering (conf: Config) extends JsConvertible {
  def alpha: Option[Double] = conf.getOptionalDouble("alpha")
  def brightness: Option[Double] = conf.getOptionalDouble("brightness")
  def contrast: Option[Double] = conf.getOptionalDouble("contrast")
  def hue: Option[Double] = conf.getOptionalDouble("hue")
  def saturation: Option[Double] = conf.getOptionalDouble("saturation")
  def gamma: Option[Double] = conf.getOptionalDouble("gamma")

  def alphaColor: Option[String] = conf.getOptionalString("alpha-color")
  def alphaColorThreshold: Option[Double] = conf.getOptionalDouble("alpha-color-threshold")


  def appendJs(sb: StringBuilder): Unit = {
    appendOptionalJsDoubleMember(sb,"alpha",alpha)
    appendOptionalJsDoubleMember(sb,"brightness",brightness)
    appendOptionalJsDoubleMember(sb, "contrast",contrast)
    appendOptionalJsDoubleMember(sb,"hue",hue)
    appendOptionalJsDoubleMember(sb, "saturation",saturation)
    appendOptionalJsDoubleMember(sb, "gamma",gamma)

    appendOptionalJsStringMember(sb, "alphaColor", alphaColor)
    appendOptionalJsDoubleMember(sb, "alphaColorThreshold",alphaColorThreshold)
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
  // this is the top parser for 'source' config entries
  def apply (conf: Config): ImgLayer = {
    val pathName = conf.getString( "pathname")
    val info = conf.getString("info")
    val provider = ClassLoaderUtils.newInstance[CesiumImageryProvider]( this, conf.getString("provider-class"),
      Array(classOf[Config]), Array(conf)).get // the secondary (provider specific) parser
    val exclusive = conf.getStringSeq("exclusive")
    val colorMap = conf.getOptionalString("color-map")
    val proxy = conf.getBooleanOrElse("proxy", false)
    val dir = conf.getOptionalString(("dir"))
    val show = conf.getBooleanOrElse("show", false)
    val renderCfg = conf.getOptionalConfig("render").map( new ImageryRendering(_))

    ImgLayer(pathName, info, provider, exclusive, proxy, show, colorMap, dir, renderCfg)
  }
}

/**
 * server model of imagery spec
 */
case class ImgLayer (pathName: String, info: String, provider: CesiumImageryProvider,
                     exclusive: Seq[String], proxy: Boolean, show: Boolean,
                     colorMap: Option[String],
                     dir: Option[String],
                     render: Option[ImageryRendering]) extends JsConvertible {

  val id = pathName.replace('/', '-') // unique id has to be a single url component

  def appendJs(sb: StringBuilder): Unit = {
    appendJsStringMember(sb, "pathName", pathName)
    appendJsStringMember(sb, "info", info)
    appendJsMember(sb, "provider")( sb=>provider.appendJs(sb))
    appendJsArrayMember(sb, "exclusive", exclusive)
    if (show) appendJsBooleanMember(sb, "show", show)
    ifSome(colorMap) { _ => appendJsStringMember(sb, "colorMap", s"${ImageryLayerRoute.clrMapPrefix}/$id.json")}
    appendOptionalJsObjectMember(sb, "render", render)
  }
}

object ImageryLayerRoute {
  val imageryPrefix = "imagery"
  val imageryPrefixMatcher = PathMatcher(imageryPrefix / "[^/?]+".r) // match the imagery prefix plus the imagery id

  val clrMapPrefix = "imagery-cmap"
  val clrMapPrefixMatcher = PathMatcher(clrMapPrefix / "[^/?]+".r) // match the resource prefix plus the imagery id

  val tmsPrefix = s"tms"
  val tmsPrefixMatcher = PathMatcher(tmsPrefix / "[^/?]+".r)

  val jsModule = "ui_cesium_imglayer.js"
  val icon = "globe-icon.svg"
  val windowId = "imglayer"
}
import ImageryLayerRoute._

/**
 * a RaceRouteInfo for Cesium Imagery Layers
 */
trait ImageryLayerRoute extends CesiumRoute with JsonProducer {
  private val defaultRendering = new DefaultImageryRendering(config.getConfigOrElse("imglayer.render", NoConfig))
  private val sources = config.getConfigSeq("imglayer.sources").map(ImgLayer(_))

  private val proxyMap = createProxyMap(sources) // proxy-name -> external url
  private val colorMap = createColorMap(sources) // pathName -> internal path
  private val tmsMap = createTmsMap(sources) // url -> tile root dirs

  def createProxyMap (imgLayers: Seq[ImgLayer]): Map[String,String] = {
    imgLayers.foldLeft(Map.empty[String,String]) { (map, layer) =>
      if (layer.proxy) {
        val id = layer.id
        val provider = layer.provider
        provider.setProxied(s"$imageryPrefix/$id") // has to go into the js instantiation code
        map + (id -> provider.url)
      } else map
    }
  }

  def createColorMap (imgLayers: Seq[ImgLayer]): Map[String,String] = {
    imgLayers.foldLeft(Map.empty[String,String]) { (map, layer) =>
      if (layer.colorMap.isDefined) {
        val id = s"${layer.id}.json"
        map + (id -> layer.colorMap.get)
      } else map
    }
  }

  def createTmsMap (imgLayers: Seq[ImgLayer]): Map[String,String] = {
    val prefix = s"$tmsPrefix/"
    imgLayers.foldLeft(Map.empty[String,String]) { (map, layer) =>
      val url = layer.provider.url
      if (url.startsWith(prefix) && layer.dir.isDefined) {
        map + (url.substring(prefix.length) -> layer.dir.get)
      } else map
    }
  }

  //--- route
  override def route: Route = imgLayerRoute ~ super.route

  def imgLayerRoute: Route = {
      get {
        pathPrefix(imageryPrefixMatcher) { layerId =>
          proxyMap.get(layerId) match {
            case Some(url) => completeProxied(url)
            case None => complete(StatusCodes.NotFound, s"$imageryPrefix/$layerId")
          }
        } ~
        pathPrefix(tmsPrefixMatcher) { layerId =>
          extractUnmatchedPath { p =>
            val path = p.toString()
            tmsMap.get(layerId) match {
              case Some(dir) =>
                FileUtils.fileContentsAsBytes(new File(dir, path)) match {
                  case Some(data) => complete( ResponseData.forExtension( FileUtils.getExtension(path),data))
                  case None => complete(StatusCodes.NotFound, s"$tmsPrefix/$layerId$p") // no such file in layer dir
                }
              case None => complete(StatusCodes.NotFound, s"$tmsPrefix/$layerId$p") // unknown layer
            }
          }
        } ~
        pathPrefix(clrMapPrefixMatcher) { cmapId =>
          colorMap.get(cmapId) match {
            case Some(fileName) =>
              // not worth caching unless we have hundreds of users
              FileUtils.fileContentsAsBytes(fileName) match {
                case Some(bs) => complete( ResponseData.forExtension( FileUtils.getExtension(fileName), bs))
                case None => complete( StatusCodes.NotFound, cmapId)
              }
            case None => complete( StatusCodes.NotFound, s"$clrMapPrefix/$cmapId")
          }
        } ~
          fileAssetPath(jsModule) ~
          fileAssetPath(icon)
      }
  }

  //--- document fragments

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiImgLayerResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiImgLayerWindow(), uiImgLayerIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + imgLayerConfig(requestUri,remoteAddr)

  def uiImgLayerResources: Seq[Text.TypedTag[String]] =  Seq( extModule(jsModule))

  def uiImgLayerWindow (title: String="Imagery Layers"): Text.TypedTag[String] = {
    uiWindow(title, windowId, icon)(
      cesiumLayerPanel(windowId, "main.toggleShowImgLayer(event)"),
      uiPanel("sources", true)(
        uiTreeList(s"$windowId.source.list", maxRows = 15, minWidthInRem = 25, selectAction="main.selectImgLayerSrc(event)"),
        uiText(s"$windowId.source.info", maxWidthInRem = 25)
      ),
      uiPanel("color map", false)(
        uiList(s"$windowId.cm.list", maxRows = 15, selectAction = "main.selectImgCmapEntry(event)"),
        uiText(s"$windowId.cm.info", maxWidthInRem = 25)
      ),
      uiPanel("layer parameters", false)(
        uiColumnContainer("align_right")(
          uiSlider("alpha", "imglayer.render.alpha", "main.setImgAlpha(event)"),
          uiSlider("brightness", "imglayer.render.brightness", "main.setImgBrightness(event)"),
          uiSlider("contrast", "imglayer.render.contrast", "main.setImgContrast(event)"),
          uiSlider("hue", "imglayer.render.hue", "main.setImgHue(event)"),
          uiSlider("saturation", "imglayer.render.saturation","main.setImgSaturation(event)"),
          uiSlider("gamma", "imglayer.render.gamma", "main.setImgGamma(event)")
        )
      )
    )
  }

  def uiImgLayerIcon: Text.TypedTag[String] = uiIcon(icon, s"main.toggleWindow(event,'$windowId')", "imglayer_icon")

  def imgLayerConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("imglayer")
    s"""
export const imglayer = {
  ${cesiumLayerConfig(cfg, "/background/imagery", "static imagery")},
  sources: ${StringUtils.mkString(sources, "[\n    ", ",\n    ",   "  \n]")(src=> src.toJsObject)},
  render: ${defaultRendering.toJsObject}
};"""
  }

    //--- no websocket messages (yet?)
}

//--- configurable cesium map service abstractions (TODO - needs more complete constructor options

trait CesiumImageryProvider extends JsConvertible {
  val config: Config

  val url: String = config.getString("url")
  protected var clientUrl: String = url

  def setProxied (proxyUrl: String): Unit = clientUrl = proxyUrl
}

class DefaultImageryProvider (val config: Config) extends CesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = sb.append("null") // nothing to instantiate
}

class ArcGisMapServerImageryProvider (val config: Config) extends CesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append("new Cesium.ArcGisMapServerImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    sb.append("})")
  }
}

class OpenStreetMapImageryProvider (val config: Config) extends CesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append("new Cesium.OpenStreetMapImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    sb.append("})")
  }
}

class WebMapServiceImageryProvider (val config: Config) extends CesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append("new Cesium.WebMapServiceImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    appendJsBooleanMember(sb, "enablePickFeatures", false)
    appendOptionalJsStringMember(sb, "layers", config.getOptionalString("wms.layers"))
    appendOptionalJsStringMember(sb, "parameters", config.getOptionalString("wms.parameters"))
    sb.append("})")
  }
}

class WebMapTileServiceImageryProvider (val config: Config) extends CesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append("new Cesium.WebMapTileServiceImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    appendOptionalJsStringMember(sb, "layer", config.getOptionalString("wmts.layer"))
    appendOptionalJsStringMember(sb, "style", config.getOptionalString("wmts.style"))
    appendOptionalJsStringMember(sb, "tileMatrixSetID", config.getOptionalString("wmts.tileMatrixSetID"))
    appendOptionalJsIntMember(sb, "maximumLevel", config.getOptionalInt("wmts.maximumLevel"))
    appendOptionalJsStringMember(sb, "format", config.getOptionalString("wmts.format"))
    sb.append("})")
  }
}

class TileMapServiceImageryProvider (val config: Config) extends CesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append("new Cesium.TileMapServiceImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    sb.append("})")
  }
}

/**
 * single layer test application
 */
class CesiumImageryLayerApp (val parent: ParentActor, val config: Config) extends DocumentRoute with ImageryLayerRoute with UiSettingsRoute

