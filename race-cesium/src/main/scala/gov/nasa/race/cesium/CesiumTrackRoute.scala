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
import gov.nasa.race.cesium.CesiumTrackRoute.cesiumCss
import gov.nasa.race.common.CachedFile
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{BasicWSContext, CachedProxyRoute, ContinuousTimeRaceRoute, IncomingConnectionHeader, TokenizedWSRaceRoute, TrackWSRoute}
import gov.nasa.race.ui._
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.{ClassUtils, FileUtils}
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable

object CesiumTrackRoute {
  val resourceDir = "./race-cesium/src/main/resources/gov/nasa/race/cesium"

  val cesiumScript = new CachedFile( s"$resourceDir/cesiumTracks.js",
    ClassUtils.getResourceAsBytes(getClass,"cesiumTracks.js").get)
  val cesiumCss = new CachedFile( s"$resourceDir/cesiumTracks.css",
    ClassUtils.getResourceAsBytes(getClass,"cesiumTracks.css").get)
  val cesiumTrackModel = new CachedFile( s"$resourceDir/ppp.glb",
    ClassUtils.getResourceAsBytes(getClass,"ppp.glb").get)

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
class CesiumTrackRoute (val parent: ParentActor, val config: Config) extends TrackWSRoute
        with CachedProxyRoute with ContinuousTimeRaceRoute with TokenizedWSRaceRoute with UiServerRoute {
  val uiPath = config.getStringOrElse("ui-path", UiServerRoute.uiSourcePath)
  val uiThemeCss = config.getStringOrElse("ui-theme", UiServerRoute.uiDefaultTheme)

  val accessToken = config.getVaultableString("access-token")

  val cesiumCache = config.getOptionalString("cesium-cache") // optional cache of Cesium resources
  val cesiumVersion = config.getStringOrElse("cesium-version", "1.88")

  val trackColor = config.getStringOrElse("color", "yellow")
  val trackModel = loadTrackModel( config.getStringOrElse("track-model", "ppp.glb"), trackColor)

  val trackColors = config.getKeyValuePairsOrElse("track-colors", Seq.empty)

  val imageryProvider = config.getStringOrElse("maptile-provider", "http://tile.stamen.com/terrain")
  val imageryCache = config.getOptionalString("maptile-cache")

  val terrainProvider = config.getStringOrElse("elevation-provider", "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")
  val terrainCache = config.getOptionalString("elevation-cache")

  val trackCss = config.getStringOrElse("track-css", "cesiumTracks.css")
  val trackScript = config.getStringOrElse("track-script", "cesiumTracks.js")

  var configScript = "" // set on demand once we know our request authority

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

  // TODO we might cache these as they are invariant (unless we add cookie support for client specific settings)

  def sendSourceList (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    var srcList = channelMap.values
    if (srcList.isEmpty) srcList = config.getStrings("read-from")

    val msg = s"""{"sources":${srcList.mkString("[\"","\",\"","\"]")}}"""
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
    sendSourceList(remoteAddr, queue)
    sendInitialCameraPosition(remoteAddr, queue)
  }

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {  // [AUTH] - the static SPA content (matching the requestPrefix)
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, getContent()))
      }  ~ path("cesiumTracks.js") { // ]AUTH] - main SPA script
        complete( HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getCesiumScript()))
      } ~ path("config.js") { // [AUTH] - contains session tokens & urls
        extractUri { uri =>
          headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
            val wsToken = registerTokenForClient(sockConn.remoteAddress)
            complete(HttpEntity(ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getConfigScript(uri, wsToken)))
          }
        }
      } ~ pathPrefix(requestPrefixMatcher / "ws") { // [AUTH] - the per-client websocket for dynamic data updates
        completeTokenizedWSRoute(true)

      }~ path("cesiumTracks.css") {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), getCesiumCss()))
      } ~ path( CesiumTrackRoute.modelResource) {
        complete( HttpEntity( ContentType(MediaType.customBinary("model","gltf-binary",Compressible)), getCesiumTrackModel()))
      } ~ path( "mapcursor.png") {
        complete(HttpEntity(ContentType(MediaType.customBinary("image", "png", Compressible)), getMapCursor()))

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
      } ~ uiAssetRoute
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
        cssLink(uiThemeCss),
        cssLink("ui.css"),

        extModule("ui_data.js"),
        extModule("ui_util.js"),
        extModule("ui.js"),

        // cesium resources
        cssLink(cesiumWidgetCSS),
        extScript(cesiumUrl),

        // app config and script
        cssLink("cesiumTracks.css"),
        extModule("config.js"),
        extModule("cesiumTracks.js") // the SPA script
      ),
      body(onload:="app.initialize()", onunload:="app.shutdown()")(
        fullWindowCesiumContainer(),

        uiWindow("Time", "time")(
          uiClock("time", "time.utc", "UTC"),
          uiTimer("elapsed", "time.elapsed")
        ),

        uiWindow("View", "view")(
          uiFieldGroup()(
            uiNumField("lat", "view.latitude"),
            uiNumField("lon", "view.longitude"),
            uiNumField("alt", "view.altitude")
          ),
          uiRowContainer()(
            uiCheckBox("fullscreen", "app.toggleFullScreen(event)"),
            uiButton("Home", "app.setHomeView()"),
            uiButton("Down", "app.setDownView()")
          )
        ),

        uiWindow("Tracks","tracks")(
          uiList("tracks.sources", 5, "app.selectSource(event)", NoAction, "app.popupMenu(event,'tracks.sources_menu')")(
            uiPopupMenu("tracks.sources_menu")(
              uiMenuItem("show", "app.toggleShowSource(event)",NoId, true),
              hr(),
              uiMenuItem("show all", "app.showAllSources(event)")
            )
          ),

          uiTextInput("query","tracks.query", "app.queryTracks(event)", "enter track query"),
          uiList("tracks.list", 10, "app.selectTrack(event)"),

          uiRowContainer()(
            uiCheckBox("show path", "app.toggleShowPath(event)", "tracks.path"),
            uiRadio("line", "app.setLinePath(event)", "tracks.line"),
            uiRadio("wall", "app.setWallPath(event)", "tracks.wall"),
            uiButton("Reset", "app.resetPaths()")
          )
        ),

        uiIcon("ui_assets/track-icon.svg", "app.toggleWindow(event,'tracks')", "track_icon"),
        uiIcon("ui_assets/time-icon.svg", "app.toggleWindow(event,'time')", "time_icon"),
        uiIcon("ui_assets/view-icon.svg", "app.toggleWindow(event,'view')", "view_icon")
      )
    ).render
  }

  /**
    * this can't be cached since it contains per-session tokens and other config data that
    * might be client/device specific
    */
  def getConfigScript (requestUri: Uri, wsToken: String): String = {
    def _int (key: String, defaultValue: Int): Int = config.getIntOrElse(key,defaultValue)
    def _double (key: String, defaultValue: Double): Double = config.getDoubleOrElse(key,defaultValue)
    def _string (key: String, defaultValue: String): String = config.getStringOrElse(key,defaultValue)

    val imagery = if (imageryCache.isDefined) CesiumTrackRoute.imageryPrefix else imageryProvider
    val terrain = if (terrainCache.isDefined) CesiumTrackRoute.terrainPrefix else terrainProvider
    val trackLabelOffsetX = _int("track-label-offset.x", 12)
    val trackLabelOffsetY = _int("track-label-offset.y", 12)
    val trackPointDist = _int("track-point-dist", 120000)

    s"""
      //--- server provided client configuration module

      export const cesiumAccessToken = '${config.getVaultableString("access-token")}';

      export const wsURL = 'ws://${requestUri.authority}/$requestPrefix/ws/$wsToken';

      export const trackColor = Cesium.Color.fromCssColorString('${_string("color", trackColor)}');
      export const trackColors = new Map(${trackColors.map(e=> s"['${e._1}',Cesium.Color.fromCssColorString('${e._2}')]").mkString("[",",","]")});

      export const trackLabelFont = '${_string("track-label", "16px sans-serif")}';
      export const trackLabelOffset = new Cesium.Cartesian2( $trackLabelOffsetX, ${_int("track-label-offset.y", 10)});
      export const trackLabelBackground = Cesium.Color.fromCssColorString('${_string( "track-label-bg", "black")}');
      export const trackLabelDC = new Cesium.DistanceDisplayCondition( 0, ${_int("track-label-dist", 200000)});

      export const trackPointSize = ${_int("track-point-size", 5)};
      export const trackPointOutlineColor = Cesium.Color.fromCssColorString('${_string("track-point-outline-color", "black")}');
      export const trackPointOutlineWidth = ${_double("track-point-outline-width", 1)};
      export const trackPointDC = new Cesium.DistanceDisplayCondition( $trackPointDist, Number.MAX_VALUE);

      export const trackModel = '${CesiumTrackRoute.modelResource}';
      export const trackModelSize = ${_int( "track-model-size", 20) };
      export const trackModelDC = new Cesium.DistanceDisplayCondition( 0, $trackPointDist);
      export const trackModelOutlineColor = Cesium.Color.fromCssColorString('${_string("track-model-outline-color", "black")}');
      export const trackModelOutlineWidth = ${_double("track-model-outline-width", 2.0)};
      export const trackModelOutlineAlpha = ${_double("track-model-outline-alpha", 1.0)};

      export const trackInfoFont = '${_string("track-info", "14px monospace")}';
      export const trackInfoOffset = new Cesium.Cartesian2( ${_int("track-info-offset.x", trackLabelOffsetX)}, ${_int("track-info-offset.y", trackLabelOffsetY + 16)});
      export const trackInfoDC = new Cesium.DistanceDisplayCondition( 0, ${_int("track-info-dist", 80000)});

      export const trackPathLength = ${_int("track-path-length", 0)};
      export const trackPathDC = new Cesium.DistanceDisplayCondition( 0, ${_int("track-path-dist", 1000000)});
      export const trackPathColor = Cesium.Color.fromCssColorString('${_string("track-path-color", trackColor)}');
      export const trackPathWidth = ${_int("track-path-width", 1)};

      export const imageryProvider = new Cesium.OpenStreetMapImageryProvider({url: '$imagery'});
      imageryProvider.defaultBrightness = ${_double("maptile-brightness", 0.6)};
      imageryProvider.defaultContrast = ${_double("maptile-contrast", 1.5)};
      imageryProvider.defaultHue = Cesium.Math.toRadians(${_int("maptile-hue", 0)}); // 220 for reddish
      imageryProvider.defaultSaturation = ${_double("maptile-saturation", 1.0)};
      imageryProvider.defaultGamma = ${_double("maptile-gamma", 1.0)};

      export const terrainProvider = new Cesium.ArcGISTiledElevationTerrainProvider({url: '$terrain'});

      export const maxTraceLength = ${_int("max-trace-length", 200)};
     """
  }

  def getCesiumScript(): Array[Byte] = CesiumTrackRoute.cesiumScript.getContentAsBytes()

  def getCesiumCss(): Array[Byte] = CesiumTrackRoute.cesiumCss.getContentAsBytes()

  def getCesiumTrackModel(): Array[Byte] = CesiumTrackRoute.cesiumTrackModel.getContentAsBytes()

  def getMapCursor(): Array[Byte] = CesiumTrackRoute.mapCursor
}
