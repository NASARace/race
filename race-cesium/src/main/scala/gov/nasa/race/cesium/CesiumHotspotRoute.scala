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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.http.{ContinuousTimeRaceRoute, DocumentRoute, PushWSRaceRoute, WSContext}
import gov.nasa.race.earth.{Hotspot, Hotspots}
import gov.nasa.race.ui.{NoAction, NoId, extModule, uiButton, uiCheckBox, uiColumnContainer, uiIcon, uiList, uiMenuItem, uiPanel, uiPopupMenu, uiRadio, uiRowContainer, uiSlider, uiTextInput, uiWindow}
import gov.nasa.race.util.StringUtils
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

object CesiumHotspotRoute {
  val HOTSPOTS = asc("hotspots")
  val HOURS = asc("hours")
  val COLOR = asc("color")

  val defaultTimeSteps: Seq[HotspotTimeStep] = Seq(
    HotspotTimeStep( 6, "#ff0000ff"),
    HotspotTimeStep( 12, "#e00000b2"),
    HotspotTimeStep( 24, "#c8000080"),
    HotspotTimeStep( 36, "#ad000066")
  )

  val defaultTempThreshold = BrightThreshold(310,"#ffff00")
  val defaultFrpThreshold = FrpThreshold(10, "#000000")
}
import CesiumHotspotRoute._

/**
  * a RaceRouteInfo that shows satellite hotspots
  */
trait CesiumHotspotRoute extends CesiumRoute with PushWSRaceRoute with ContinuousTimeRaceRoute with PipedRaceDataClient {
  private val writer = new JsonWriter()

  protected val hotspots = mutable.ArrayDeque.empty[Hotspots[_]]

  //--- the layer config (TODO - refactor)
  private val layerName = config.getStringOrElse("hotspot.name","hotspot")
  private val layerPath = config.getStringOrElse("hotspot.cat", "/fire/detection")
  private val layerDescription = config.getStringOrElse("hotspot.description", "VIIRS/MODIS satellite hotspots")
  private val layerShow = config.getBooleanOrElse("hotspot.show", true)
  private val hotspotHistory = config.getFiniteDurationOrElse("hotspot.history", 7.days) // in days
  private val angularResolution = config.getDoubleOrElse("hotspot.grid-resolution", 0.0) // 0.0001 is about 10m in conus

  private val timeSteps = getHotspotTimeSteps()
  private val tempThreshold = config.getOptionalConfig("hotspot.temp").map { c =>
    BrightThreshold( c.getInt("threshold"), c.getString("color"))
  }.getOrElse(defaultTempThreshold)
  private val frpThreshold = config.getOptionalConfig("hotspot.frp").map { c=>
    FrpThreshold( c.getInt("threshold"), c.getString("color"))
  }.getOrElse(defaultFrpThreshold)


  def getHotspotTimeSteps(): Seq[HotspotTimeStep] = {
    val cfgs = config.getConfigSeq("hotspot.time-steps")
    if (cfgs.isEmpty) {
      defaultTimeSteps
    } else {
      cfgs.map{ c=> HotspotTimeStep( c.getInt("hours"), c.getString("color")) }
    }
  }

  //--- route

  override def route: Route = uiCesiumHotspotRoute ~ super.route

  def uiCesiumHotspotRoute: Route = {
    get {
      fileAssetPath("ui_cesium_hotspot.js") ~
        fileAssetPath("hotspot-icon.svg")
    }
  }

  //--- document content

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumHotspotResources

  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiHotspotWindow(), uiHotspotIcon)

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri,remoteAddr) + hotspotConfig(requestUri,remoteAddr)
  }

  def uiCesiumHotspotResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule("ui_cesium_hotspot.js"))
  }

  def uiHotspotWindow(title: String="Hotspots"): Text.TypedTag[String] = {
    uiWindow(title,"hotspot", "hotspot-icon.svg")(
      uiColumnContainer()(
        uiCheckBox(s" show $layerPath/$layerName", "main.toggleShowHotspots(event)", NoId, layerShow),
        p(layerDescription)
      ),
      uiColumnContainer()(
        uiList("hotspot.list", 10, "main.selectHotspotTime(event)")
      ),
      uiRowContainer()(
        uiButton("⊼", "main.latestHotspotTime()"),
        uiButton("⋀︎", "main.laterHotspotTime()"),
        uiButton("⋁︎", "main.earlierHotspotTime()"),
        uiButton("⊻", "main.earliestHotspotTime()"),
        uiButton("clear", "main.clearHotspots()")
      ),
      uiPanel("layer parameters", false)(
        uiColumnContainer("align_right")(
          uiSlider("history [d]", "hotspot.history", "main.setHotspotHistory(event)"),
          uiSlider("grid resolution [°]", "hotspot.resolution", "main.setHotspotResolution(event)")
        )
      )
    )
  }

  def uiHotspotIcon: Text.TypedTag[String] = {
    uiIcon("hotspot-icon.svg", "main.toggleWindow(event,'hotspot')", "hotspot_icon")
  }

  def hotspotConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    s"""export const hotspot = {
  name: '$layerName',
  path: '$layerPath',
  show: $layerShow,
  resolution: $angularResolution,
  history: ${hotspotHistory.toHours/24},
  timeSteps: ${StringUtils.mkString(timeSteps,"[\n    ", ",\n    ", "  ]")(_.toConfigString())},
  temp: ${tempThreshold.toConfigString()},
  frp: ${frpThreshold.toConfigString()}
};"""
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection( ctx, queue)
    initializeHotspotConnection( ctx, queue)
  }

  def initializeHotspotConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    synchronized {
      hotspots.foreach { hs=>
        val msg = serializeHotspots(hs)
        pushTo(ctx.remoteAddress, queue, TextMessage.Strict(msg))
      }
    }
  }

  override def receiveData: Receive = receiveHotspotData.orElse(super.receiveData)

  def receiveHotspotData: Receive = {
    case BusEvent(channel,hs:Hotspots[_],sender) =>
      synchronized {
        hotspots += hs
        if (hasConnections) push( TextMessage.Strict(serializeHotspots(hs)))
      }
    case BusEvent(channel,h: Hotspot,sender) =>
  }

  def serializeHotspots( hs: Hotspots[_]): String = {
    writer.clear().writeObject(w=> w.writeObjectMember(HOTSPOTS)( hs.serializeMembersTo)).toJson
  }

  def dropOldHotspots(): Unit = {
    val cutoff = dateTime - hotspotHistory

    synchronized {
      while (hotspots.head.date < cutoff) {
        val hs = hotspots.head
        val msg = s"""{"dropHotspots":${hs.date.toEpochMillis}}"""
        push(TextMessage.Strict(msg))
        hotspots.dropInPlace(1)
      }
    }
  }
}

/**
  * simple service to show hotspots
  */
class CesiumHotspotApp (val parent: ParentActor, val config: Config) extends DocumentRoute with CesiumHotspotRoute