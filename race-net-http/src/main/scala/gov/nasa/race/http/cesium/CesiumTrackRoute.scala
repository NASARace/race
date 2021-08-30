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
package gov.nasa.race.http.cesium

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, MediaTypes}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{BasicWSContext, TrackWSRoute}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.util.ClassUtils

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable

object CesiumTrackRoute {
  val htmlContent = ClassUtils.getResourceAsString(getClass,"index.html").get
  val cesiumScript = ClassUtils.getResourceAsString(getClass,"cesiumTracks.js").get
  val cesiumCSS = ClassUtils.getResourceAsString(getClass,"cesiumTracks.css").get
}

/**
  * a TrackRoute that uses Cesium to display tracks as Cesium.Entities
  */
class CesiumTrackRoute (val parent: ParentActor, val config: Config) extends TrackWSRoute {
  val accessToken = config.getVaultableString("access-token")
  val trackColor = config.getStringOrElse("color", "red");

  def sendInitialCameraPosition (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    // position over center of continental US if nothing specified
    var lat = 40.34
    var lon = -98.66
    var alt = Meters(4000000)  // in meters

    val ec = config.getOptionalConfig("eye")
    if (ec.isDefined) {
      val e = ec.get
      alt = e.getLengthOrElse("altitude", alt)
      lat = e.getDoubleOrElse("lat", lat)
      lon = e.getDoubleOrElse("lon", lon)
    }

    val msg = s"""{"camera":{"lat":$lat, "lon":$lon, "alt":${alt.toMeters}}}"""
    pushTo(remoteAddr, queue, TextMessage.Strict(msg))
  }

  override protected def initializeConnection (ctx: BasicWSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.sockConn.remoteAddress
    sendInitialCameraPosition(remoteAddr, queue)
  }

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {
        complete( HttpEntity( ContentTypes.`text/html(UTF-8)`, getContent()))
      } ~ path("ws") {
        info("opening websocket")
        promoteToWebSocket()
      } ~ path("config.js") {
        complete( HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getConfigScript()))
      } ~ path("cesiumTracks.css") {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), getCSS()))
      } ~ path("cesiumTracks.js") {
        complete( HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), getScript()))
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

  def getContent(): String = CesiumTrackRoute.htmlContent

  def getConfigScript(): String = {
    s"""
        Cesium.Ion.defaultAccessToken = '$accessToken';

        const wsURL = 'ws://localhost:8080/ws';  // FIXME
        const trackColor = '$trackColor';
     """
  }

  def getScript(): String = CesiumTrackRoute.cesiumScript

  def getCSS(): String = CesiumTrackRoute.cesiumCSS
}
