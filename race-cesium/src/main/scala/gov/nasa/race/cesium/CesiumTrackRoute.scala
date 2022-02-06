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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http._
import gov.nasa.race.ui._
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable

object CesiumTrackRoute  extends CachedFileAssetMap {
  val sourcePath = "./race-cesium/src/main/resources/gov/nasa/race/cesium"
}

/**
  * a RaceRoute that uses Cesium to display tracks transmitted over a websocket
  */
trait CesiumTrackRoute extends CesiumRoute with TrackWSRoute {

  val trackColor = config.getStringOrElse("color", "yellow")
  val trackModel = config.getStringOrElse("track-model", "generic_track.glb")
  val trackColors = config.getKeyValuePairsOrElse("track-colors", Seq.empty)

  def sendSourceList (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    var srcList = channelMap.values
    if (srcList.isEmpty) srcList = config.getStrings("read-from")

    val msg = s"""{"sources":${srcList.mkString("[\"","\",\"","\"]")}}"""
    pushTo(remoteAddr, queue, TextMessage.Strict(msg))
  }


  //--- route handling

  override def route: Route = uiCesiumTrackRoute ~ super.route

  def uiCesiumTrackRoute: Route = {
    get {
      path ("ui_cesium_tracks.js") {
        complete( ResponseData.js( CesiumTrackRoute.getContent("ui_cesium_tracks.js")))
      } ~ path( trackModel) {
        complete( ResponseData.glb( CesiumTrackRoute.getContent(trackModel)))
      } ~ path ("track-icon.svg") {
        complete( ResponseData.svg( CesiumTrackRoute.getContent("track-icon.svg")))
      }
    }
  }

  //--- websocket communication

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection( ctx, queue)
    initializeTrackConnection( ctx, queue)
  }

  // the track specific initialization messages sent to the client
  protected def initializeTrackConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    sendSourceList(ctx.remoteAddress, queue)
  }

  protected override def handleIncoming (ctx: WSContext, m: Message): Iterable[Message] = {
    handleIncomingTrackMessages(ctx,m)
    super.handleIncoming(ctx,m)
  }


  /**
    * this handles track related messages from the client
    * note this has to be called from the concrete type handleIncoming() method, which also has to discard the incoming message
    */
  protected def handleIncomingTrackMessages (ctx: WSContext, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parseTrackMessage(ctx, tm.text) match {
          case Some(replies) => replies
          case None => Nil // not handled
        }
      case _ => Nil // we don't process streams
    }
  }

  /**
    *  parse message from client, returning optional list of reply messages
    */
  def parseTrackMessage(ctx: WSContext, msg: String): Option[Iterable[Message]] = {
    // TBD
    None
  }

  //--- document content

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumTrackResources

  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiTrackWindow(), uiTrackIcon)

  def uiTrackWindow(title: String="Tracks"): Text.TypedTag[String] = {
    uiWindow(title,"tracks")(
      uiList("tracks.sources", 5, "main.selectSource(event)", NoAction, "main.popupMenu(event,'tracks.sources_menu')")(
        uiPopupMenu("tracks.sources_menu")(
          uiMenuItem("show", "main.toggleShowSource(event)",NoId, true),
          hr(),
          uiMenuItem("show all", "main.showAllSources(event)")
        )
      ),

      uiTextInput("query","tracks.query", "main.queryTracks(event)", "enter track query"),
      uiList("tracks.list", 10, "main.selectTrack(event)"),

      uiRowContainer()(
        uiCheckBox("show path", "main.toggleShowPath(event)", "tracks.path"),
        uiRadio("line", "main.setLinePath(event)", "tracks.line"),
        uiRadio("wall", "main.setWallPath(event)", "tracks.wall"),
        uiButton("Reset", "main.resetPaths()")
      )
    )
  }

  def uiTrackIcon: Text.TypedTag[String] = {
    uiIcon("track-icon.svg", "main.toggleWindow(event,'tracks')", "track_icon")
  }

  def uiCesiumTrackResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule("ui_cesium_tracks.js"))
  }

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri,remoteAddr) + trackConfig(requestUri,remoteAddr)
  }

  /**
    * this module can't be cached since it contains per-session tokens and other config data that
    * might be client- or request specific
    */
  def trackConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    def _int (key: String, defaultValue: Int): Int = config.getIntOrElse(key,defaultValue)
    def _double (key: String, defaultValue: Double): Double = config.getDoubleOrElse(key,defaultValue)
    def _string (key: String, defaultValue: String): String = config.getStringOrElse(key,defaultValue)


    val trackLabelOffsetX = _int("track-label-offset.x", 12)
    val trackLabelOffsetY = _int("track-label-offset.y", 12)
    val trackPointDist = _int("track-point-dist", 120000)

    s"""
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

      export const trackModel = '$trackModel';
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

      export const maxTraceLength = ${_int("max-trace-length", 200)};
     """
  }
}


object CesiumTrackApp extends CachedFileAssetMap {
  val sourcePath = "./race-cesium/src/main/resources/gov/nasa/race/cesium"
}

/**
  * a single page application that processes track channels
  */
class CesiumTrackApp (val parent: ParentActor, val config: Config) extends MainSpaRoute with CesiumTrackRoute {
  val mainModule = "main_tracks.js"
  val mainCss = "main_tracks.css"

  override def mainModuleContent: Array[Byte] = CesiumTrackApp.getContent(mainModule)
  override def mainCssContent: Array[Byte] = CesiumTrackApp.getContent(mainCss)
}