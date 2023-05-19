/*
 * Copyright (c) 2021, United States Government, as represented by the
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
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, PathMatcher, PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.cesium.CesiumService.{cesiumJsUrl, terrainPrefix}
import gov.nasa.race.common.JsConvertible
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.http._
import gov.nasa.race.ifSome
import gov.nasa.race.ui._
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.{ClassLoaderUtils, FileUtils, StringUtils}
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import java.util.TimeZone

object CesiumService {
  val terrainPrefix = "terrain"

  val defaultCesiumJsVersion = "1.105"

  val cesiumPathMatcher = PathMatchers.separateOnSlashes("Build/Cesium/")
  def cesiumJsUrl (version: String): String = {
    s"https://cesium.com/downloads/cesiumjs/releases/$version/Build/Cesium/"
  }

  def createTerrainProvider (optConf: Option[Config]): CesiumTerrainProvider = {
    optConf match {
      case Some(conf) =>
        conf.getOptionalString("class") match {
          case Some(cls) =>
            val clsName = if (cls.startsWith(".")) "gov.nasa.race" + cls else cls
            ClassLoaderUtils.newInstance[CesiumTerrainProvider]( this, clsName, Array(classOf[Config]), Array(conf)).get
          case None => new DefaultTerrainProvider(conf)
        }
      case None => new DefaultTerrainProvider()
    }
  }
}
import CesiumService._

object CameraPosition {
  def apply (conf: Config): CameraPosition = {
    CameraPosition(
      conf.getString("name"),
      GeoPosition.fromDegreesAndMeters(
        conf.getDouble("lat"), conf.getDouble("lon"), conf.getDouble("alt")
      )
    )
  }
}
case class CameraPosition (name: String, pos: GeoPosition) {
  override def toString(): String = f"{name: \"$name\", lat: ${pos.latDeg}%.5f, lon: ${pos.lonDeg}%.5f, alt: ${pos.altMeters.round}}"
}


/**
 * abstraction for a Cesium TerrainProvider that can be stored in a config.js module either
 * as a Promise.<TerrainProvider> or as a TerrainProvider object
 */
trait CesiumTerrainProvider extends JsConvertible {
  val config: Config
  def appendJs (sb:StringBuilder): Unit
}

case class DefaultTerrainProvider(config: Config) extends CesiumTerrainProvider {
  def this() = this(NoConfig)

  def appendJs (sb: StringBuilder): Unit = {
    config.getOptionalString("options") match {
      case Some(opts) => sb.append(s"Cesium.createWorldTerrainAsync($opts)")
      case None => sb.append(s"Cesium.createWorldTerrainAsync()")
    }
  }
}

trait ExternalTerrainProvider extends CesiumTerrainProvider {
  def url: String
  def clientUrl: String = if (config.getBooleanOrElse("proxy", false)) terrainPrefix else url
}

case class UrlTerrainProvider (config: Config)  extends ExternalTerrainProvider {
  val url = config.getString("url")

  def appendJs (sb: StringBuilder): Unit = {
    config.getOptionalString("options") match {
      case Some(opts) => sb.append(s"Cesium.TerrainProvider.fromUrl('$clientUrl',$opts)")
      case None => sb.append(s"Cesium.TerrainProvider.fromUrl('$clientUrl')")
    }
  }
}

case class ArcGisTiledElevationTerrainProvider (config: Config) extends ExternalTerrainProvider {
  val url = config.getStringOrElse("url","https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")

  def appendJs (sb: StringBuilder): Unit = {
    config.getOptionalVaultableString("arcgis.token") match {
      case Some(tok) => sb.append(s"Cesium.ArcGISTiledElevationTerrainProvider.fromUrl('$clientUrl',{token:'$tok'})")
      case None => sb.append(s"Cesium.ArcGISTiledElevationTerrainProvider.fromUrl('$clientUrl')")
    }
  }
}


/**
  * a RaceRouteInfo that servers Cesium related content, including:
  *  - Cesium code,
  *  - related imagery/terrain requests (which can both be cached)
  *  - Cesium related UI scripts and assets
  *
  * note that we do require a config.js to transmit the cesiumAccessToken, plus a number of settings that
  * control Cesium appearance. However, config usually also has to provide layer/dataSource (i.e. application-specific)
  * settings and hence we provide only config artifacts here
  */
trait CesiumService
  extends FSCachedProxyRoute // we might cache cesium content
    with UiRoute // we provide race-ui windows for controlling Cesium view and entities
    with CachedFileAssetRoute
    with TokenizedWSRaceRoute  // and need a per-document-request web socket..
    with PushWSRaceRoute //..to push at least initial globe/time state (concrete type probably needs to push more)
    with ConfigScriptRoute // requiring a lot of client configuration
    with ContinuousTimeRaceRoute { // we also need sim time

  //--- Cesium specific configuration

  val accessToken = config.getVaultableStringOrElse("access-token", "")
  val cesiumVersion = config.getStringOrElse("cesium-version", defaultCesiumJsVersion)
  val cameraPositions = getCameraPositions()
  val terrainProvider = createTerrainProvider(config.getOptionalConfig("terrain"))
  //val terrainProvider = config.getStringOrElse("terrain-provider", "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")

  def getCameraPositions(): Seq[CameraPosition] = {
    val places = config.getConfigSeq("camera-positions").map( cfg=> CameraPosition(cfg))
    if (places.nonEmpty) places else Seq(defaultCameraPosition)
  }

  def defaultCameraPosition = {
    config.getOptionalConfig("eye").map( cfg => CameraPosition( "home",  // legacy config
      GeoPosition.fromDegreesAndMeters(
        cfg.getDouble("lat"),
        cfg.getDouble("lon"),
        cfg.getDouble("alt")
      )
    )).getOrElse(
      CameraPosition("bay area",  GeoPosition.fromDegreesAndMeters(37.6, -122.4, 150000))
    )
  }


  //--- cesium related routes

  override def route: Route = uiCesiumRoute ~ super.route

  def uiCesiumRoute: Route = {
    get {
      //--- dynamic geospatial content requested by Cesium at runtime
      pathPrefix(CesiumService.terrainPrefix) { // also just when configured as proxy
        terrainProvider match {
          case etp: ExternalTerrainProvider => completeProxied(etp.url)
          case _ => complete(StatusCodes.NotFound)
        }

        //--- the standard Cesium assets - note we always proxy these fs-cached with a pre-configured host URL
      } ~ pathPrefix(cesiumPathMatcher) {
        completeProxied(cesiumJsUrl(cesiumVersion))
      } ~
        fileAssetPath("ui_cesium.js") ~
        fileAssetPath("ui_cesium.css") ~
        fileAssetPath("time-icon.svg") ~
        fileAssetPath("camera-icon.svg") ~
        fileAssetPath("layer-icon.svg") ~
        fileAssetPath("map-cursor.png")
    }
    // TODO - we should have a path for own/proxied terrain
  }

  //--- cesium web socket handling

  protected override def initializeConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection( ctx, queue)
    initializeCesiumConnection( ctx,queue)
  }

  protected def initializeCesiumConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.remoteAddress
    sendSetClock( remoteAddr, queue)
  }

  def sendCameraPosition (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    // position over center of continental US if nothing specified
    var lat = 40.34
    var lon = -98.66
    var alt = Meters(4000000)  // in meters

    val ec = config.getOptionalConfig("eye")
    if (ec.isDefined) {
      val e = ec.get
      alt = e.getLengthOrElse("alt", alt)
      lat = e.getDoubleOrElse("lat", lat)
      lon = e.getDoubleOrElse("lon", lon)
    }

    val msg = s"""{"camera":{"lat":$lat, "lon":$lon, "alt":${alt.toMeters}}}"""
    pushTo(remoteAddr, queue, TextMessage.Strict(msg))
  }

  def sendSetClock (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    val msg = s"""{"setClock":{"time":${simClock.millis}, "timeScale":${simClock.timeScale}}}"""
    pushTo(remoteAddr, queue, TextMessage.Strict(msg))
  }

  //--- document content fragments

  // we need to load that before any of our own scripts so that we can refer to it during init (e.g. in scripts)
  override def getPreambleHeaderFragments: Seq[Text.TypedTag[String]] = super.getPreambleHeaderFragments ++ cesiumResources

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumResources

  override def getPreambleBodyFragments: Seq[Text.TypedTag[String]] = super.getPreambleBodyFragments :+ fullWindowCesiumContainer

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri, remoteAddr) + basicCesiumConfig(requestUri,remoteAddr)
  }

  // to be called from the concrete getConfig() implementation
  def basicCesiumConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val places = cameraPositions.mkString(",\n    ")

    s"""
export const cesium = {
  accessToken: '$accessToken',
  terrainProvider: ${terrainProvider.toJs},
  requestRenderMode: ${config.getBooleanOrElse("request-render", false)},
  targetFrameRate: ${config.getIntOrElse("frame-rate", -1)},
  cameraPositions: [\n    $places\n  ],
  localTimeZone: '${config.getStringOrElse("local-timezone", TimeZone.getDefault.getID)}',
  color: ${cesiumColor(config, "color", "red")},
  outlineColor: ${cesiumColor(config, "outline-color", "yellow")},
  font: '${config.getStringOrElse("label-font", "16px sans-serif")}',
  labelBackground: ${cesiumColor(config, "label-bg", "#00000060")},
  pointSize: ${config.getIntOrElse("point-size", 3)},
};
"""
  }

  // id required by Cesium scripts
  def fullWindowCesiumContainer: Text.TypedTag[String] = div(id:="cesiumContainer", cls:="ui_full_window")

  def cesiumResources: Seq[Text.TypedTag[String]] = {
    // note these are now always fs-cache proxied so we can use fixed (logical) URLs
    Seq(
      cssLink("Build/Cesium/Widgets/widgets.css"),
      extScript("Build/Cesium/Cesium.js")
    )
  }

  def uiCesiumResources: Seq[Text.TypedTag[String]] = {
    Seq(
      cssLink("ui_cesium.css"),
      addJsModule("ui_cesium.js")
    )
  }

  //--- misc
  protected def cesiumColor(cfg: Config, key: String, fallback: =>String): String = {
    s"""Cesium.Color.fromCssColorString('${cfg.getStringOrElse(key, fallback)}')"""
  }

  //--- to be used as config and window fragments by concrete CesiumRoutes with configurable layer info

  def cesiumLayerConfig (cfg: Config, layerName: String, layerDescription: String, show: Boolean=true): String = {
    s"""layer: {
    name: "${cfg.getStringOrElse("layer-name", layerName)}",
    description: "${cfg.getStringOrElse("description", layerDescription)}",
    show: ${cfg.getBooleanOrElse("show", show)},
  }"""
  }

  def cesiumLayerPanel (wid: String, showAction: String, showInitial:Boolean = true): Text.TypedTag[String] = {
    uiPanel("layer", false, s"$wid.layer")(
      uiLabel( wid+".layer-descr")
    )
  }
}
