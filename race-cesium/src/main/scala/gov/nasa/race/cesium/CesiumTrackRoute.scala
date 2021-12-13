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

import akka.http.scaladsl.model.MediaType.Compressible
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{BasicWSContext, CachedProxyRoute, ContinuousTimeRaceRoute, TrackWSRoute}
import gov.nasa.race.ui._
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.{ClassUtils, FileUtils}
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable

object CesiumTrackRoute {
  val htmlContent = ClassUtils.getResourceAsUtf8String(getClass,"index.html").get
  val cesiumScript = ClassUtils.getResourceAsUtf8String(getClass,"cesiumTracks.js").get
  val cesiumCSS = ClassUtils.getResourceAsUtf8String(getClass,"cesiumTracks.css").get
  val mapCursor = ClassUtils.getResourceAsBytes(getClass, "mapcursor-bw-32x32.png").get

  val controlsIcon = ClassUtils.getResourceAsBytes(getClass, "controls.svg").get

  //--- symbolic names for resources that can be proxied
  val imageryPrefix = "imagery"
  val terrainPrefix = "terrain"
  val modelResource = "model"
}

/**
  * a TrackRoute that uses Cesium to display tracks as Cesium.Entities
  */
class CesiumTrackRoute (val parent: ParentActor, val config: Config) extends TrackWSRoute with CachedProxyRoute with ContinuousTimeRaceRoute {
  val accessToken = config.getVaultableString("access-token")

  val cesiumCache = config.getOptionalString("cesium-cache") // optional cache of Cesium resources
  val cesiumVersion = config.getStringOrElse("cesium-version", "1.87")

  val trackColor = config.getStringOrElse("color", "yellow")
  val trackModel = loadTrackModel( config.getStringOrElse("track-model", "paper-plane.glb"), trackColor)

  val imageryProvider = config.getStringOrElse("maptile-provider", "http://tile.stamen.com/terrain")
  val imageryCache = config.getOptionalString("maptile-cache")

  val terrainProvider = config.getStringOrElse("elevation-provider", "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")
  val terrainCache = config.getOptionalString("elevation-cache")

  val trackCSS = config.getStringOrElse("track-css", "cesiumTracks.css")
  val trackScript = config.getStringOrElse("track-script", "cesiumTracks.js")

  var configScript = "" // set on demand once we know our request authority

  def loadConfigScript (requestUri: Uri): String = {
    def _int (key: String, defaultValue: Int): Int = config.getIntOrElse(key,defaultValue)
    def _double (key: String, defaultValue: Double): Double = config.getDoubleOrElse(key,defaultValue)
    def _string (key: String, defaultValue: String): String = config.getStringOrElse(key,defaultValue)

    val imagery = if (imageryCache.isDefined) CesiumTrackRoute.imageryPrefix else imageryProvider
    val terrain = if (terrainCache.isDefined) CesiumTrackRoute.terrainPrefix else terrainProvider
    val trackLabelOffsetX = _int("track-label-offset.x", 12)
    val trackLabelOffsetY = _int("track-label-offset.y", 12)
    val trackPointDist = _int("track-point-dist", 120000)

    s"""
      //--- server provided client configuration

      Cesium.Ion.defaultAccessToken = '${config.getVaultableString("access-token")}';

      const wsURL = 'ws://${requestUri.authority}/ws';
      const trackColor = Cesium.Color.fromCssColorString('${_string("color", trackColor)}');

      const trackLabelFont = '${_string("track-label", "16px sans-serif")}';
      const trackLabelOffset = new Cesium.Cartesian2( $trackLabelOffsetX, ${_int("track-label-offset.y", 12)});
      const trackLabelBackground = Cesium.Color.fromCssColorString('${_string( "track-label-bg", "black")}');
      const trackLabelDC = new Cesium.DistanceDisplayCondition( 0, ${_int("track-label-dist", 200000)});

      const trackPointSize = ${_int("track-point-size", 5)};
      const trackPointOutlineColor = Cesium.Color.fromCssColorString('${_string("track-point-outline-color", "black")}');
      const trackPointOutlineWidth = ${_double("track-point-outline-width", 1.5)};
      const trackPointDC = new Cesium.DistanceDisplayCondition( $trackPointDist, Number.MAX_VALUE);

      const trackModel = '${CesiumTrackRoute.modelResource}';
      const trackModelSize = ${_int( "track-model-size", 26) };
      const trackModelDC = new Cesium.DistanceDisplayCondition( 0, $trackPointDist);
      const trackModelOutlineColor = Cesium.Color.fromCssColorString('${_string("track-model-outline-color", "black")}');
      const trackModelOutlineWidth = ${_double("track-model-outline-width", 2.0)};
      const trackModelOutlineAlpha = ${_double("track-model-outline-alpha", 1.0)};

      const trackInfoFont = '${_string("track-info", "14px sans-serif")}';
      const trackInfoOffset = new Cesium.Cartesian2( ${_int("track-info-offset.x", trackLabelOffsetX)}, ${_int("track-info-offset.y", trackLabelOffsetY + 16)});
      const trackInfoDC = new Cesium.DistanceDisplayCondition( 0, ${_int("track-info-dist", 80000)});

      const trackPathLength = ${_int("track-path-length", 0)};
      const trackPathDC = new Cesium.DistanceDisplayCondition( 0, ${_int("track-path-dist", 1000000)});
      const trackPathColor = Cesium.Color.fromCssColorString('${_string("track-path-color", trackColor)}');
      const trackPathWidth = ${_int("track-path-width", 0)};

      const imageryProvider = new Cesium.OpenStreetMapImageryProvider({url: '$imagery'});
      imageryProvider.defaultBrightness = ${_double("maptile-brightness", 0.6)};
      imageryProvider.defaultContrast = ${_double("maptile-contrast", 1.5)};
      imageryProvider.defaultHue = Cesium.Math.toRadians(${_int("maptile-hue", 0)}); // 220 for reddish
      imageryProvider.defaultSaturation = ${_double("maptile-saturation", 1.0)};
      imageryProvider.defaultGamma = ${_double("maptile-gamma", 1.0)};

      const terrainProvider = new Cesium.ArcGISTiledElevationTerrainProvider({url: '$terrain'});

      const maxTraceLength = ${_int("max-trace-length", 100)};
     """
  }

  def loadTrackModel (fname: String, clrSpec: String): Array[Byte] = {
    ClassUtils.getResourceAsBytes(getClass, fname) match {
      case Some(data) => data
      case None =>
        warning(s"no model $fname")
        Array[Byte](0)
    }
  }

  def sendSetClock (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    val msg = s"""{"setClock":{"time":${simClock.millis}, "timescale":${simClock.timeScale}}}"""
    pushTo(remoteAddr, queue, TextMessage.Strict(msg))
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

  override protected def initializeConnection (ctx: BasicWSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.sockConn.remoteAddress
    sendSetClock(remoteAddr, queue)
    sendInitialCameraPosition(remoteAddr, queue)
  }

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {  // the static SPA content
        complete( HttpEntity( ContentTypes.`text/html(UTF-8)`, getContent()))
      } ~ path("ws") { // the dynamic data updates
        info("opening websocket")
        promoteToWebSocket()

      } ~ path("config.js") {
        extractUri { uri =>
          complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getConfigScript(uri)))
        }
      } ~ path("cesiumTracks.css") {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), getCSS()))
      } ~ path("cesiumTracks.js") {
        complete( HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getScript()))
      } ~ path( CesiumTrackRoute.modelResource) {
        complete( HttpEntity( ContentType(MediaType.customBinary("model","gltf-binary",Compressible)), getModel()))
      } ~ path( "mapcursor.png") {
        complete(HttpEntity(ContentType(MediaType.customBinary("image", "png", Compressible)), getMapCursor()))

        //--- race-client-ui artifacts
      } ~ path ("ui.js") {
        complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), uiScript))
      } ~ path ("ui_util.js") {
        complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), uiUtilScript))
      } ~ path ("ui_data.js") {
        complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), uiDataScript))
      } ~ path ("ui.css") {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), uiCSS))
      } ~ path ("ui_theme.css") {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), uiThemeDarkCSS)) // FIXME - should be user specific

      } ~ path( CesiumTrackRoute.modelResource) {
          complete( HttpEntity( ContentType(MediaType.customBinary("model","gltf-binary",Compressible)), getModel()))

      } ~ pathPrefix( CesiumTrackRoute.imageryPrefix) { // we only get this if we act as a proxy
        completeCached( imageryCache.get, imageryProvider)
      } ~ pathPrefix( CesiumTrackRoute.terrainPrefix) { // also just when configured as proxy
        completeCached( terrainCache.get, terrainProvider)

      } ~ pathPrefix( "Build" / "Cesium") {
        extractUnmatchedPath { p =>
          val pathName = cesiumCache.get + "/Build/Cesium" + p.toString()
          FileUtils.fileContentsAsString( pathName) match {
            case Some(content) => complete( HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), content))
            case None => complete( StatusCodes.NotFound, "Build/Cesium" + p.toString())
          }
        }

      } ~ pathPrefix( "ui_assets") {
        extractUnmatchedPath { p =>
          val pn = p.toString().substring(1)
          ClassUtils.getResourceAsBytes(getClass, pn) match {
            case Some(content) =>
              if (pn.endsWith(".svg")) complete( StatusCodes.OK, HttpEntity(MediaTypes.`image/svg+xml`,content))
              else if (pn.endsWith(".png")) complete(HttpEntity(ContentType(MediaType.customBinary("image", "png", Compressible)), content))
              else complete( StatusCodes.NotAcceptable, "unsupported content type: " + pn)
            case None =>complete( StatusCodes.NotFound, "ui_assets" + pn)

          }
        }
      }
    }
  }

  /**
    *  parse message from client, returning optional list of reply messages
    */
  def parseMessage(ctx: BasicWSContext, msg: String): Option[Iterable[Message]] = {
    // TBD
    None
  }

  /**
    * this is what we get from user devices through their web sockets
    */
  override protected def handleIncoming (ctx: BasicWSContext, m: Message): Iterable[Message] = {
    val response = m match {
      case tm: TextMessage.Strict =>
        parseMessage(ctx, tm.text) match {
          case Some(replies) => replies
          case None => Nil // not handled
        }
      case _ => Nil // we don't process streams
    }
    discardMessage(m)
    response
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

  //--- content getters (can be overridden in subclasses)

  def getContent(): String = {
    html(
      htmlHead(
        // race-client-ui resources
        cssLink("ui_theme.css"),
        cssLink("ui.css"),

        extModule("ui_data.js"),
        extModule("ui_util.js"),
        extModule("ui.js"),

        // cesium resources
        cssLink(cesiumWidgetCSS),
        extScript(cesiumUrl),

        // app config and script
        cssLink("cesiumTracks.css"),
        extScript("config.js"),
        extModule("cesiumTracks.js") // the SPA script
      ),
      body(onload:="app.initialize()", onunload:="app.shutdown()")(
        fullWindowCesiumContainer(),

        uiWindow("Track Console", "console")(
          uiPanel("Clock",true, "console.clock")(
            uiClock("time", "console.clock.time", "UTC"),
            uiTimer("elapsed", "console.clock.elapsed")
          ),
          uiPanel("View",true, "console.view")(
            uiFieldGroup()(
              uiNumField("lat", "console.view.latitude"),
              uiNumField("lon", "console.view.longitude"),
              uiNumField("alt", "console.view.altitude")
            ),
            uiRowContainer()(
              uiButton("Reset", "app.setHomeView()"),
              uiButton("Down", "app.setDownView()")
            )
          ),
          uiPanel("Tracks", true, "console.tracks")(
            uiTextInput("query","console.tracks.query", "app.queryTracks()", "enter track query"),
            uiList("console.tracks.list", 10,"app.selectTrack(event)"),
            uiRowContainer()(
              uiCheckBox("show path", "app.toggleShowPath()"),
              uiRadio("line", "app.setLinePath(event)"),
              uiRadio("wall", "app.setWallPath(event)"),
              uiButton("Reset", "app.resetPath(event)")
            )
          ),
        ),

        uiIcon("ui_assets/controls.svg", "app.toggleWindow('console')", "console_icon")
      )
    ).render
  }

  def getConfigScript(requestUri: Uri): String = {
    // TODO - is this the same for all clients ?
    if (configScript.isEmpty) {
      configScript = loadConfigScript(requestUri)
    }
    configScript
  }

  def getScript(): String = CesiumTrackRoute.cesiumScript

  def getCSS(): String = CesiumTrackRoute.cesiumCSS

  def getModel(): Array[Byte] = trackModel

  def getMapCursor(): Array[Byte] = CesiumTrackRoute.mapCursor
}
