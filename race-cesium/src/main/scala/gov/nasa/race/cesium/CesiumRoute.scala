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
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.http._
import gov.nasa.race.ui.{uiSlider, _}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.FileUtils
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import java.util.TimeZone

case class ImageryProvider (name: String, url: String, providerCls: String, description: String, proxy: Boolean)

object CesiumRoute extends CachedFileAssetMap {
  def sourcePath = "./race-cesium/src/main/resources/gov/nasa/race/cesium"

  val imageryPrefix = "imagery"
  val terrainPrefix = "terrain"

  val defaultImageryProviders: Seq[ImageryProvider] = Seq(
    ImageryProvider(
      "arcgis",
      "https://services.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/",
      "Cesium.ArcGisMapServerImageryProvider",
      "arcgis geographic",
      false),
    ImageryProvider(
      "stamen_terrain",
      "http://tile.stamen.com/terrain",
      "Cesium.OpenStreetMapImageryProvider",
      "stamen terrain",
      false),
    ImageryProvider(
      "<default>",
      "<default>",
      "<default>",
      "Bing aerial",
      false
    )
  )

  val defaultOverlayProviders: Seq[ImageryProvider] = Seq(
    ImageryProvider(
      "goes_conus_ir",
      "https://mesonet.agron.iastate.edu/cgi-bin/wms/goes/conus_ir.cgi?",
      "Cesium.WebMapServiceImageryProvider",
      "goes conus infrared",
      false),
    ImageryProvider(
      "nexrad",
      "https://mesonet.agron.iastate.edu/cgi-bin/wms/nexrad/n0r.cgi?",
      "Cesium.WebMapServiceImageryProvider",
      "nexrad precipitation",
      false)
  )
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
trait CesiumRoute
  extends FSCachedProxyRoute // we might cache cesium content
    with UiRoute // we provide race-ui windows for controlling Cesium view and entities
    with TokenizedWSRaceRoute  // and need a per-document-request web socket..
    with PushWSRaceRoute //..to push at least initial globe/time state (concrete type probably needs to push more)
    with ConfigScriptRaceRoute // requiring a lot of client configuration
    with ContinuousTimeRaceRoute { // we also need sim time

  //--- Cesium specific configuration

  val accessToken = config.getVaultableString("access-token")

  val cesiumCache = config.getOptionalString("cesium-cache") // optional cache of Cesium resources
  val cesiumVersion = config.getStringOrElse("cesium-version", "1.91")

  val proxyImagery = config.getBoolean("proxy-maptile-provider")
  val imageryProvider = config.getStringOrElse("maptile-provider", "http://tile.stamen.com/terrain")

  val proxyTerrain = config.getBoolean("proxy-elevation-provider")
  val terrainProvider = config.getStringOrElse("elevation-provider", "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")

  val imageryProviders = {
    // read from config first
    CesiumRoute.defaultImageryProviders
  }

  val overlayProviders = {
    // read from config first
    CesiumRoute.defaultOverlayProviders
  }

  //--- cesium related routes

  override def route: Route = uiCesiumRoute ~ super.route

  def uiCesiumRoute: Route = {
    get {
      //--- dynamic geospatial content requested by Cesium at runtime
      pathPrefix(CesiumRoute.imageryPrefix) { // we only get this if we act as a proxy
        completeProxied(imageryProvider)
      } ~ pathPrefix(CesiumRoute.terrainPrefix) { // also just when configured as proxy
        completeProxied(terrainProvider)

        //--- the standard Cesium assets
      } ~ pathPrefix("Build" / "Cesium") {
        extractUnmatchedPath { p =>
          val pathName = cesiumCache.get + "/Build/Cesium" + p.toString()
          FileUtils.fileContentsAsString(pathName) match {
            case Some(content) => complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), content))
            case None => complete(StatusCodes.NotFound, "Build/Cesium" + p.toString())
          }
        }

        //--- our Cesium assets
      } ~ path ("ui_cesium.js") {
        complete( ResponseData.js( CesiumRoute.getContent("ui_cesium.js")))
      } ~ path ("ui_cesium.css") {
        complete( ResponseData.css( CesiumRoute.getContent("ui_cesium.css")))
      } ~ path ("time-icon.svg") {
        complete( ResponseData.svg( CesiumRoute.getContent("time-icon.svg")))
      } ~ path ("view-icon.svg") {
        complete( ResponseData.svg( CesiumRoute.getContent("view-icon.svg")))
      } ~ path ("map-cursor.png") {
        complete( ResponseData.png( CesiumRoute.getContent("map-cursor-bw-32x32.png")))
      }
    }
  }

  //--- cesium web socket handling

  protected override def initializeConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection( ctx, queue)
    initializeCesiumConnection( ctx,queue)
  }

  protected def initializeCesiumConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.remoteAddress
    sendSetClock( remoteAddr, queue)
    sendInitialCameraPosition( remoteAddr, queue)
  }

  def sendInitialCameraPosition (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
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

  override def getBodyFragments: Seq[Text.TypedTag[String]] = {
    super.getBodyFragments ++ Seq(uiViewWindow(), uiViewIcon, uiTimeWindow(), uiTimeIcon)
  }

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri, remoteAddr) + basicCesiumConfig(requestUri,remoteAddr)
  }

  def createMapLayerProviders (providers: Seq[ImageryProvider]): String = {
    val sb = new StringBuilder
    sb.append("[")
    providers.foreach { ip=>
      if (ip.name == "<default>") {
        sb.append(s"\n{name:'', provider: undefined}") // the default Bing aerial provider
      } else {
        val url = if (ip.proxy) s"${CesiumRoute.imageryPrefix}/${ip.name}" else ip.url
        sb.append(s"\n{name:'${ip.name}',provider: new ${ip.providerCls}({url:'$url'})},")
      }
    }
    sb.append("\n]")
    sb.toString()
  }

  // to be called from the concrete getConfig() implementation
  def basicCesiumConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {

    val wsToken = registerTokenForClient(remoteAddr)

    val imagery = if (proxyImagery) CesiumRoute.imageryPrefix else imageryProvider
    val terrain = if (proxyTerrain) CesiumRoute.terrainPrefix else terrainProvider

    s"""
      export const cesiumAccessToken = '${config.getVaultableString("access-token")}';
      export const wsURL = 'ws://${requestUri.authority}/$requestPrefix/ws/$wsToken';

      //export const imageryProvider = new Cesium.ArcGisMapServerImageryProvider({url:'https://services.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/'});
      export const imageryProvider = new Cesium.ArcGisMapServerImageryProvider({url: '$imagery'});
      //export const imageryProvider = new Cesium.OpenStreetMapImageryProvider({url: '$imagery'});

      export const imageryProviders = ${createMapLayerProviders(imageryProviders)};
      export const imageryProviderOptions = {
        defaultBrightness: ${config.getDoubleOrElse("maptile-brightness", 0.6)},
        defaultContrast: ${config.getDoubleOrElse("maptile-contrast", 1.5)},
        defaultHue: Cesium.Math.toRadians(${config.getIntOrElse("maptile-hue", 0)}), // 220 for reddish
        defaultSaturation: ${config.getDoubleOrElse("maptile-saturation", 1.0)},
        defaultGamma: ${config.getDoubleOrElse("maptile-gamma", 1.0)},
        defaultAlpha: ${config.getDoubleOrElse("maptile-gamma", 1.0)}
      };

      export const overlayProviders = ${createMapLayerProviders(overlayProviders)};

      export const terrainProvider = new Cesium.ArcGISTiledElevationTerrainProvider({url: '$terrain'});
    """
  }

  def cesiumUrl: String = {
    cesiumCache match {
      case Some(path) => "Build/Cesium/Cesium.js"
      case None => s"https://cesium.com/downloads/cesiumjs/releases/$cesiumVersion/Build/Cesium/Cesium.js"
    }
  }

  def cesiumWidgetCSS: String = {
    cesiumCache match {
      case Some(path) => "Build/Cesium/Widgets/widgets.css"
      case None => s"https://cesium.com/downloads/cesiumjs/releases/$cesiumVersion/Build/Cesium/Widgets/widgets.css"
    }
  }

  // id required by Cesium scripts
  def fullWindowCesiumContainer: Text.TypedTag[String] = div(id:="cesiumContainer", cls:="ui_full_window")

  def cesiumResources: Seq[Text.TypedTag[String]] = {
    Seq(
      cssLink(cesiumWidgetCSS),
      extScript(cesiumUrl)
    )
  }

  def uiCesiumResources: Seq[Text.TypedTag[String]] = {
    Seq(
      cssLink("ui_cesium.css"),
      extModule("ui_cesium.js")
    )
  }

  //--- the standard Ui windows/icons we provide (can be overridden for more app specific versions)

  // positions can be set in main css (ids: 'view', 'view_icon', 'time', 'time_icon')

  def uiViewWindow(title: String="View"): Text.TypedTag[String] = {
    uiWindow(title, "view")(
      uiFieldGroup()(
        uiNumField("lat", "view.latitude"),
        uiNumField("lon", "view.longitude"),
        uiNumField("alt", "view.altitude")
      ),
      uiRowContainer()(
        uiCheckBox("fullscreen", "main.toggleFullScreen(event)"),
        uiButton("Home", "main.setHomeView()"),
        uiButton("Down", "main.setDownView()")
      ),
      uiPanel("maps", false)(
        uiColumnContainer("align_left")(
          imageryProviders.map( p=> uiRadio(p.description, s"main.setImageryProvider(event,'${p.name}')"))
        )
      ),
      uiPanel("map overlays", false)(
        uiColumnContainer("align_left")(
          overlayProviders.map( p=> uiCheckBox(p.description, s"main.toggleOverlayProvider(event,'${p.name}')"))
        )
      ),
      uiPanel("map appearance", false)(
        uiColumnContainer("align_right")(
          uiSlider("brightness", "view.map.brightness", "main.setMapBrightness(event)"),
          uiSlider("contrast", "view.map.contrast", "main.setMapContrast(event)"),
          uiSlider("hue", "view.map.hue", "main.setMapHue(event)"),
          uiSlider("saturation", "view.map.saturation","main.setMapSaturation(event)"),
          uiSlider("gamma", "view.map.gamma", "main.setMapGamma(event)"),
          uiSlider("alpha", "view.map.alpha", "main.setMapAlpha(event)")
        )
      )
    )
  }

  def uiViewIcon: Text.TypedTag[String] = {
    uiIcon("view-icon.svg", "main.toggleWindow(event,'view')", "view_icon")
  }

  def uiTimeWindow(title: String="Time"): Text.TypedTag[String] = {
    uiWindow(title, "time")(
      uiClock("time UTC", "time.utc", "UTC"),
      uiClock("time loc", "time.loc",  TimeZone.getDefault.getID),
      uiTimer("elapsed", "time.elapsed")
    )
  }

  def uiTimeIcon: Text.TypedTag[String] = {
    uiIcon("time-icon.svg", "main.toggleWindow(event,'time')", "time_icon")
  }
}
