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
import gov.nasa.race.cesium.CesiumRoute.cesiumJsUrl
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.http._
import gov.nasa.race.ui.{uiRadio, uiRowContainer, uiSlider, _}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.{FileUtils, StringUtils}
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import java.util.TimeZone


object CesiumRoute {
  val terrainPrefix = "terrain"

  val defaultCesiumJsVersion = "1.101"

  val cesiumPathMatcher = PathMatchers.separateOnSlashes("Build/Cesium/")
  def cesiumJsUrl (version: String): String = {
    s"https://cesium.com/downloads/cesiumjs/releases/$version/Build/Cesium/"
  }
}
import CesiumRoute._

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

  val accessToken = config.getVaultableStringOrElse("access-token", "")
  val cesiumVersion = config.getStringOrElse("cesium-version", defaultCesiumJsVersion)
  val requestRenderMode = config.getBooleanOrElse("request-render", false)
  val targetFrameRate = config.getIntOrElse("frame-rate", -1)
  val proxyTerrain = config.getBooleanOrElse("proxy-elevation-provider", true)
  val terrainProvider = config.getStringOrElse("elevation-provider", "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")
  val cameraPositions = getCameraPositions()

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
      pathPrefix(CesiumRoute.terrainPrefix) { // also just when configured as proxy
        completeProxied(terrainProvider)

        //--- the standard Cesium assets - note we always proxy these fs-cached with a pre-configured host URL
      } ~ pathPrefix(cesiumPathMatcher) {
        completeProxied(cesiumJsUrl(cesiumVersion))

        //--- our Cesium assets
      } ~
        fileAssetPath("ui_cesium.js") ~
        fileAssetPath("ui_cesium.css") ~
        fileAssetPath("time-icon.svg") ~
        fileAssetPath("camera-icon.svg") ~
        fileAssetPath("layer-icon.svg") ~
        fileAssetPath("map-cursor.png")
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

  override def getBodyFragments: Seq[Text.TypedTag[String]] = {
    super.getBodyFragments ++ Seq(uiViewWindow(), uiViewIcon, uiTimeWindow(), uiTimeIcon, uiModuleLayerWindow(), uiModuleLayerIcon)
  }

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri, remoteAddr) + basicCesiumConfig(requestUri,remoteAddr)
  }

  // to be called from the concrete getConfig() implementation
  def basicCesiumConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val wsToken = registerTokenForClient(remoteAddr)
    val terrain = if (proxyTerrain) CesiumRoute.terrainPrefix else terrainProvider
    // new Cesium.CesiumTerrainProvider({ url: Cesium.IonResource.fromAssetId(1),}),
    val places = cameraPositions.mkString(",\n    ")

    s"""
export const wsUrl = 'ws://${requestUri.authority}/$requestPrefix/ws/$wsToken';
export const cesium = {
  accessToken: '$accessToken',
  requestRenderMode: $requestRenderMode,
  targetFrameRate: $targetFrameRate,
  terrainProvider: new Cesium.ArcGISTiledElevationTerrainProvider({url: '$terrain'}),
  cameraPositions: [\n    $places\n  ],
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
      extModule("ui_cesium.js")
    )
  }

  //--- the standard Ui windows/icons we provide (can be overridden for more app specific versions)

  // positions can be set in main css (ids: 'view', 'view_icon', 'time', 'time_icon')

  def uiViewWindow(title: String="View"): Text.TypedTag[String] = {
    val bw = 3.5
    uiWindow(title, "view", "camera-icon.svg")(
      uiRowContainer()(
        uiCheckBox("fullscreen", "main.toggleFullScreen(event)"),
        uiHorizontalSpacer(1),
        uiRadio("pointer", "main.setDisplayPointerLoc(event)", "view.showPointer"),
        uiRadio( "camera", "main.setDisplayCameraLoc(event)", "view.showCamera"),
        uiHorizontalSpacer(1),
        uiButton("⟘", "main.setDownView()", 2.5),  // ⇩  ⊾ ⟘
        uiButton("⌂", "main.setHomeView()", 2.5) // ⌂ ⟐ ⨁
      ),
      uiRowContainer()(
        uiButton(text = "⨀", eid = "view.pickPos", action = "main.pickPoint()", widthInRem = 2.5),
        uiButton("⨁", "main.addPoint()", 2.5),
        uiTextInput("", "view.latitude", isFixed = true, action = "main.setViewFromFields()", width = "5rem"),
        uiTextInput("", "view.longitude", isFixed = true, action = "main.setViewFromFields()", width = "6rem"),
        uiTextInput("", "view.altitude", isFixed = true, action = "main.setViewFromFields()", width = "5.2rem"),
        uiHorizontalSpacer(0.4)
      ),
      uiList("view.positions", 8, dblClickAction = "main.setCameraFromSelection(event)"),
      uiRowContainer()(
        uiChoice("","view.posSet", "main.selectPositionSet(event)"),
        uiButton("save", "main.storePositionSet()", bw),
        uiButton("del", "main.removePositionSet()", bw),
        uiHorizontalSpacer(4),
        uiButton("⌫", "main.removePoint()", 2.5)
      ),
      uiPanel("view parameters", false)(
        uiCheckBox("render on-demand", "main.toggleRequestRenderMode()", "view.rm"),
        uiSlider("frame rate", "view.fr", "main.setFrameRate(event)")
      )
    )
  }

  def uiViewIcon: Text.TypedTag[String] = {
    uiIcon("camera-icon.svg", "main.toggleWindow(event,'view')", "view_icon")
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

  def uiModuleLayerWindow(title: String="Layers"): Text.TypedTag[String] = {
    uiWindow(title, "layer", "layer-icon.svg")(
      uiPanel("order", true)(
        uiList("layer.order", 10),
        uiRowContainer()(
          uiButton("↑", "main.raiseModuleLayer()"),
          uiButton("↓", "main.lowerModuleLayer()")
        )
      ),
      uiPanel("hierarchy", false)(
        uiList("layer.hierarchy", 15)
      )
    )
  }

  def uiModuleLayerIcon: Text.TypedTag[String] = {
    uiIcon("layer-icon.svg", "main.toggleWindow(event,'layer')", "layer_icon")
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
