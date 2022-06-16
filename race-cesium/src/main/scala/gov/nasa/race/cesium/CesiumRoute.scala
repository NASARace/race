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
import akka.http.scaladsl.server.{PathMatcher, PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import gov.nasa.race.cesium.CesiumRoute.cesiumJsUrl
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.http._
import gov.nasa.race.ui.{uiSlider, _}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.{FileUtils, StringUtils}
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import java.util.TimeZone


object CesiumRoute {

  val imageryPrefix = "imagery"
  val terrainPrefix = "terrain"

  val imageryPrefixMatcher = PathMatcher(imageryPrefix / "[^/]+".r ~ Slash)

  val defaultCesiumJsVersion = "1.94"

  val cesiumPathMatcher = PathMatchers.separateOnSlashes("Build/Cesium/")
  def cesiumJsUrl (version: String): String = {
    s"https://cesium.com/downloads/cesiumjs/releases/$version/Build/Cesium/"
  }
}
import CesiumRoute._

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
    with CachedFileAssetRoute
    with TokenizedWSRaceRoute  // and need a per-document-request web socket..
    with PushWSRaceRoute //..to push at least initial globe/time state (concrete type probably needs to push more)
    with ConfigScriptRoute // requiring a lot of client configuration
    with ContinuousTimeRaceRoute { // we also need sim time

  //--- Cesium specific configuration

  val accessToken = config.getVaultableString("access-token")
  val cesiumVersion = config.getStringOrElse("cesium-version", defaultCesiumJsVersion)
  val requestRenderMode = config.getBooleanOrElse("request-render", false)
  val targetFrameRate = config.getIntOrElse("frame-rate", -1)
  val proxyTerrain = config.getBooleanOrElse("proxy-elevation-provider", true)
  val terrainProvider = config.getStringOrElse("elevation-provider", "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")
  val imageryLayers = ImageryLayer.readConfig(config)
  val layerMap: Map[String,String] = Map.from( imageryLayers.map( layer=> (layer.name, layer.url))) // symbolic map layer name -> url

  // these can be overridden by what is in the theme, which can be overridden what is specified in the layer itself
  // (if the layer has a spec that prevents anything but manual modification on the client side)
  val imageryParams = ImageryLayer.getDefaultImageryParams(config.getOptionalConfig("imagery-params"))

  //--- cesium related routes

  override def route: Route = uiCesiumRoute ~ super.route

  def uiCesiumRoute: Route = {
    get {
      //--- dynamic geospatial content requested by Cesium at runtime
      pathPrefix(CesiumRoute.imageryPrefixMatcher) { layerId=>// we only get this if we act as a proxy
          layerMap.get(layerId) match {
            case Some(url) => completeProxied(url)
            case None => complete(StatusCodes.NotFound, p.toString())
          }

      } ~ pathPrefix(CesiumRoute.terrainPrefix) { // also just when configured as proxy
        completeProxied(terrainProvider)

        //--- the standard Cesium assets - note we always proxy these fs-cached with a pre-configured host URL
      } ~ pathPrefix(cesiumPathMatcher) {
        completeProxied( cesiumJsUrl(cesiumVersion))

        //--- our Cesium assets
      } ~
        fileAsset ("ui_cesium.js") ~
        fileAsset ("ui_cesium.css") ~
        fileAsset ("time-icon.svg") ~
        fileAsset ("view-icon.svg") ~
        fileAsset ("map-cursor.png")
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

  // to be called from the concrete getConfig() implementation
  def basicCesiumConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {

    val wsToken = registerTokenForClient(remoteAddr)

    val terrain = if (proxyTerrain) CesiumRoute.terrainPrefix else terrainProvider
      s"""
export const cesiumAccessToken = '${config.getVaultableString("access-token")}';
export const wsUrl = 'ws://${requestUri.authority}/$requestPrefix/ws/$wsToken';
export const requestRenderMode = $requestRenderMode;
export const targetFrameRate = $targetFrameRate;
export const imageryParams = ${imageryParams.toJs};
export const imageryLayers = ${StringUtils.mkString(imageryLayers,"[\n  ",",\n  ","\n]")(_.toJs)};
export const terrainProvider = new Cesium.ArcGISTiledElevationTerrainProvider({url: '$terrain'});
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
      extModule("ui_cesium.js")
    )
  }

  //--- the standard Ui windows/icons we provide (can be overridden for more app specific versions)

  // positions can be set in main css (ids: 'view', 'view_icon', 'time', 'time_icon')

  def uiViewWindow(title: String="View"): Text.TypedTag[String] = {
    uiWindow(title, "view", "view-icon.svg")(
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
      uiColumnContainer("align_right")(
        uiCheckBox("render on-demand", "main.toggleRequestRenderMode()", "view.rm"),
        uiSlider("frame rate", "view.fr", "main.setFrameRate(event)")
      ),
      uiPanel("map layers", true)(
        uiList("view.map.list", 10, "main.selectMapLayer(event)"),
      ),
      uiPanel("layer parameters", false)(
        uiColumnContainer("align_right")(
          uiSlider("alpha", "view.map.alpha", "main.setMapAlpha(event)"),
          uiSlider("brightness", "view.map.brightness", "main.setMapBrightness(event)"),
          uiSlider("contrast", "view.map.contrast", "main.setMapContrast(event)"),
          uiSlider("hue", "view.map.hue", "main.setMapHue(event)"),
          uiSlider("saturation", "view.map.saturation","main.setMapSaturation(event)"),
          uiSlider("gamma", "view.map.gamma", "main.setMapGamma(event)")
        )
      )
    )
  }

  def uiViewIcon: Text.TypedTag[String] = {
    uiIcon("view-icon.svg", "main.toggleWindow(event,'view')", "view_icon")
  }

  def uiTimeWindow(title: String="Time"): Text.TypedTag[String] = {
    uiWindow(title, "time", "time-icon.svg")(
      uiClock("time UTC", "time.utc", "UTC"),
      uiClock("time loc", "time.loc",  TimeZone.getDefault.getID),
      uiTimer("elapsed", "time.elapsed")
    )
  }

  def uiTimeIcon: Text.TypedTag[String] = {
    uiIcon("time-icon.svg", "main.toggleWindow(event,'time')", "time_icon")
  }

}
