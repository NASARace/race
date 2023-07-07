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
    val exclusive = conf.getStringSeq("exclusive")
    val colorMap = conf.getOptionalString("color-map")
    val proxy = conf.getBooleanOrElse("proxy", false)
    val dir = conf.getOptionalString(("dir"))
    val show = conf.getBooleanOrElse("show", false)
    val renderCfg = conf.getOptionalConfig("render").map( new ImageryRendering(_))
    val provider = createCesiumImageryProvider(conf.getOptionalConfig("provider"))

    ImgLayer(pathName, info, provider, exclusive, proxy, show, colorMap, dir, renderCfg)
  }

  def createCesiumImageryProvider (optConf: Option[Config]): CesiumImageryProvider = {
    optConf match {
      case Some(conf) =>
        conf.getOptionalString("class") match {
          case Some(cls) =>
            val clsName = if (cls.startsWith(".")) "gov.nasa.race" + cls else cls
            ClassLoaderUtils.newInstance[CesiumImageryProvider]( this, clsName, Array(classOf[Config]), Array(conf)).get
          case None => new DefaultImageryProvider(conf)
        }
      case None => new DefaultImageryProvider()
    }
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
    provider.appendJs(sb) // this can add several different properties depending on type
    appendJsArrayMember(sb, "exclusive", exclusive)
    if (show) appendJsBooleanMember(sb, "show", show)
    ifSome(colorMap) { _ => appendJsStringMember(sb, "colorMap", s"${ImageryLayerService.clrMapPrefix}/$id.json")}
    appendOptionalJsObjectMember(sb, "render", render)
  }
}

//--- configurable cesium map service abstractions

/**
 * wrapper for JS code that creates Cesium.ImageryProvider promises
 * note that some concrete ImageryProvider types are still directly instantiated, hence the appendJS methods
 * have to return expressions that can be wrapped into Promise.resolve(..) calls
 */

trait CesiumImageryProvider extends JsConvertible {
  val config: Config
  def appendJs (sb:mutable.StringBuilder): Unit
}

trait UrlCesiumImageryProvider extends CesiumImageryProvider {
  val url: String = config.getString("url")
  protected var clientUrl: String = url

  def setProxied (proxyUrl: String): Unit = clientUrl = proxyUrl
}

class DefaultImageryProvider (val config: Config) extends CesiumImageryProvider {
  def this() = this(NoConfig)
  def appendJs (sb: StringBuilder): Unit = {
      ifSome(config.getOptionalString("style")) {s=> sb.append(s",style: Cesium.IonWorldImageryStyle.${s.toUpperCase()}")
    }
  }
}

class ArcGisMapServerImageryProvider (val config: Config) extends UrlCesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append(s",provider: Cesium.ArcGisMapServerImageryProvider.fromUrl('$clientUrl')")
  }
}

class OpenStreetMapImageryProvider (val config: Config) extends UrlCesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append(",provider: new Cesium.OpenStreetMapImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    sb.append("})")
  }
}

class WebMapServiceImageryProvider (val config: Config) extends UrlCesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append(",provider: new Cesium.WebMapServiceImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    appendJsBooleanMember(sb, "enablePickFeatures", false)
    appendOptionalJsStringMember(sb, "layers", config.getOptionalString("layers"))
    appendOptionalJsStringMember(sb, "parameters", config.getOptionalString("parameters"))
    sb.append("})")
  }
}

class WebMapTileServiceImageryProvider (val config: Config) extends UrlCesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    sb.append(",provider: new Cesium.WebMapTileServiceImageryProvider({")
    appendJsStringMember(sb, "url", clientUrl)
    appendOptionalJsStringMember(sb, "layer", config.getOptionalString("layer"))
    appendOptionalJsStringMember(sb, "style", config.getOptionalString("style"))
    appendOptionalJsStringMember(sb, "tileMatrixSetID", config.getOptionalString("tileMatrixSetID"))
    appendOptionalJsIntMember(sb, "maximumLevel", config.getOptionalInt("maximumLevel"))
    appendOptionalJsStringMember(sb, "format", config.getOptionalString("format"))
    sb.append("})")
  }
}

/**
 * used for gdal2tiles generated imagery
 */
class TileMapServiceImageryProvider (val config: Config) extends UrlCesiumImageryProvider {
  def appendJs (sb: StringBuilder): Unit = {
    config.getOptionalConfig("tms.rectangle") match {
      case Some(cfg) =>
        sb.append(s",provider: Cesium.TileMapServiceImageryProvider.fromUrl('$clientUrl',{rectangle:{")
        sb.append(cfg.getDouble("west").toRadians); sb.append(",")
        sb.append(cfg.getDouble("south").toRadians); sb.append(",")
        sb.append(cfg.getDouble("east").toRadians); sb.append(",")
        sb.append(cfg.getDouble("north").toRadians)
        sb.append("}})")

      case None => sb.append(s",provider: Cesium.TileMapServiceImageryProvider.fromUrl('$clientUrl')")
    }
  }
}

object ImageryLayerService {
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
import ImageryLayerService._

/**
 * a RaceRouteInfo for Cesium Imagery Layers
 */
trait ImageryLayerService extends CesiumService with JsonProducer {
  private val defaultRendering = new DefaultImageryRendering(config.getConfigOrElse("imglayer.render", NoConfig))
  private val sources = config.getConfigSeq("imglayer.sources").map(ImgLayer(_))

  private val proxyMap = createProxyMap(sources) // proxy-name -> external url
  private val colorMap = createColorMap(sources) // pathName -> internal path
  private val tmsMap = createTmsMap(sources) // url -> tile root dirs

  def createProxyMap (imgLayers: Seq[ImgLayer]): Map[String,String] = {
    imgLayers.foldLeft(Map.empty[String,String]) { (map, layer) =>
      layer.provider match {
        case provider: UrlCesiumImageryProvider =>
          if (layer.proxy) {
            val id = layer.id
            provider.setProxied(s"$imageryPrefix/$id") // has to go into the js instantiation code
            map + (id -> provider.url)
          } else map
        case _ => map
      }
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
      layer.provider match {
        case provider: TileMapServiceImageryProvider =>
          val url = provider.url
          if (url.startsWith(prefix) && layer.dir.isDefined) {
            map + (url.substring(prefix.length) -> layer.dir.get)
          } else map
        case _ => map
      }
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

  def uiImgLayerResources: Seq[Text.TypedTag[String]] =  Seq( addJsModule(jsModule))

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + imgLayerConfig(requestUri,remoteAddr)

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

/**
 * single layer test application
 */
class CesiumImageryLayerApp (val parent: ParentActor, val config: Config) extends DocumentRoute with ImageryLayerService with UiSettingsRoute

