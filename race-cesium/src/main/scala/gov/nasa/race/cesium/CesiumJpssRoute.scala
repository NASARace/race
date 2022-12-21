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
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.JsonProducer
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, ParentActor, RaceDataClient}
import gov.nasa.race.earth.ViirsHotspots
import gov.nasa.race.http.{ContinuousTimeRaceRoute, DocumentRoute, PushWSRaceRoute, WSContext}
import gov.nasa.race.space.{OverpassRegion, OverpassSeq, SatelliteInfo}
import gov.nasa.race.ui._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.StringUtils
import scalatags.Text

import java.net.InetSocketAddress
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

object CesiumJpssRoute {
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

  val jsModule = "ui_cesium_jpss.js"
  val icon = "polar-sat-icon.svg"
}
import gov.nasa.race.cesium.CesiumJpssRoute._


/**
  * a RaceRouteInfo that serves active fires detected by polar orbiting satellites
  * note this can handle multiple satellites
  */
trait CesiumJpssRoute extends CesiumRoute with PushWSRaceRoute with ContinuousTimeRaceRoute with RaceDataClient with JsonProducer {

  val jpssAssets = getSymbolicAssetMap("jpss.assets", config, Seq(("fire","fire.png")))

  //--- the satellites we expect (from config)
  val jpssSatellites = config.getConfigSeq("jpss.satellites").map(c=> new SatelliteInfo(c))

  //--- the satellites and data we get from our source (dynamic data)
  val jpssRegions = mutable.Map.empty[Int, OverpassRegion] // satId -> overpassRegion
  val jpssOverpasses = mutable.SortedMap.empty[DateTime,OverpassSeq]
  val jpssHotspots = mutable.SortedMap.empty[DateTime,ViirsHotspots]

  private val hotspotMaxAge = config.getFiniteDurationOrElse("history", 14.days) // how long until we purge hotspots


  //--- route

  override def route: Route = uiCesiumJpssRoute ~ super.route

  def uiCesiumJpssRoute: Route = {
    get {
      pathPrefix( "jpss-asset" ~ Slash) {
        extractUnmatchedPath { p =>
          completeWithSymbolicAsset(p.toString, jpssAssets)
        }
      } ~
      fileAssetPath(jsModule) ~
      fileAssetPath(icon)
    }
  }

  //--- document content

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumJpssResources

  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiJpssWindow(), uiJpssIcon)

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri,remoteAddr) + jpssConfig(requestUri,remoteAddr)
  }

  def uiCesiumJpssResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule(jsModule))
  }

  def uiJpssWindow(title: String="Polar Satellites"): Text.TypedTag[String] = {
    uiWindow(title,"jpss", icon)(
      cesiumLayerPanel("jpss", "main.toggleShowJpss(event)"),
      uiPanel("satellites", true)(
        uiList("jpss.satellites", 5, "main.selectJpssSatellite(event)"),
        uiRowContainer()(
          uiColumnContainer()(
            uiTextInput("area","jpss.bounds", true, "main.setJpssBounds(event)", "enter lat,lon bounds (WSEN order)", width="20rem"),
            uiLabel("jpss.bounds-info")
          ),
          uiButton("pick", "main.pickJpssBounds()"),
          uiButton("clear", "main.clearJpssBounds()"),
          uiButton( "zoom", "main.zoomToJpssBounds()")
        )
      ),
      uiPanel("overpasses:")(
        uiRowContainer()(
          uiRowContainer(title="upcoming overpasses")(
            uiList("jpss.upcoming", 5)
          ),
          uiHorizontalSpacer(0.5),
          uiRowContainer(title="completed overpasses")(
            uiList("jpss.past", 5, "main.selectJpssPast(event)")
          )
        ),
        uiRowContainer()(
          uiCheckBox("show history", "main.toggleJpssShowPastHistory(event)", "jpss.show_history"),
          uiHorizontalSpacer(2),
          uiListControls("jpss.past")
        )
      ),
      uiPanel("hotspots", false)(
        uiList("jpss.hotspots", 10, dblClickAction = "main.zoomToJpssPixel(event)")
      ),
      uiPanel("layer parameters", false)(
        uiRowContainer()(
          uiColumnContainer("align_right")(
            uiSlider("history [d]", "jpss.history", "main.setJpssHistory(event)"),
            uiSlider("bright [K]", "jpss.bright", "main.setJpssBrightThreshold(event)"),
            uiSlider("frp [MW]", "jpss.frp", "main.setJpssFrpThreshold(event)")
          ),
          uiColumnContainer("align_right")(
            uiSlider("size [pix]", "jpss.pixsize", "main.setJpssPixelSize(event)"),
            uiSlider("outline [pix]", "jpss.outline", "main.setJpssOutlineWidth(event)"),
            uiSlider("grid [°]", "jpss.resolution", "main.setJpssResolution(event)")
          )
        )
      )
    )
  }

  def uiJpssIcon: Text.TypedTag[String] = {
    uiIcon(icon, "main.toggleWindow(event,'jpss')", "jpss_icon")
  }

  def jpssConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("jpss")
    val latLonResolution = cfg.getDoubleOrElse("grid-resolution", 0.0) // 0.0001 is about 10m in conus
    val pixelSize = cfg.getIntOrElse("pixel-size", 3)
    val timeSteps = getHotspotTimeSteps(cfg)
    val brightThreshold = getBrightThreshold(cfg)
    val frpThreshold = getFrpThreshold(cfg)

    // TODO - add frpScale: new Cesium.Cesium.NearFarScalar(nearDist, nearScaleValue, farDist, farScaleValue)

    s"""export const jpss = {
  ${cesiumLayerConfig(cfg, "/fire/tracking/JPSS", "active fire detection using polar orbiting satellites")},
  history: ${hotspotMaxAge.toHours/24},
  timeSteps: ${StringUtils.mkString(timeSteps,"[\n    ", ",\n    ", "  ]")(_.toConfigString())},
  bright: ${brightThreshold.toConfigString()},
  frp: ${frpThreshold.toConfigString()},
  pixelSize: $pixelSize,
  outlineWidth: ${cfg.getIntOrElse("outline-width", 1)},
  resolution: $latLonResolution,
  swathColor: ${cesiumColor(cfg, "swath-color", "#ff000040")},
  trackColor: ${cesiumColor(cfg, "track-color", "#ff0000ff")},
  labelColor: ${cesiumColor(cfg, "label-color", "#ffff00ff")},
  regionColor: ${cesiumColor(cfg, "region-color", "#00ffffff")},
  font: '${cfg.getStringOrElse("font", "bold 14px monospace")}',
  swathDC: new Cesium.DistanceDisplayCondition( ${cfg.getIntOrElse("swath-dist", 150000)}, Number.MAX_VALUE)
};
"""
  }

  def getBrightThreshold(conf: Config): BrightThreshold = {
    conf.getOptionalConfig("bright").map { c =>
      BrightThreshold( c.getInt("threshold"), c.getString("color"))
    }.getOrElse(defaultTempThreshold)
  }

  def getFrpThreshold (conf: Config): FrpThreshold = {
    conf.getOptionalConfig("frp").map { c=>
      FrpThreshold( c.getInt("threshold"), c.getString("color"))
    }.getOrElse(defaultFrpThreshold)
  }

  def getHotspotTimeSteps (conf: Config): Seq[HotspotTimeStep] = {
    val cfgs = conf.getConfigSeq("time-steps")
    if (cfgs.isEmpty) {
      defaultTimeSteps
    } else {
      cfgs.map{ c=> HotspotTimeStep( c.getInt("hours"), c.getString("color")) }
    }
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection( ctx, queue)
    initializeJpssConnection( ctx, queue)
  }

  def initializeJpssConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    synchronized {
      pushTo( ctx.remoteAddress, queue, TextMessage.Strict( serializeJpssSatellites))
      jpssRegions.foreach(e=> pushTo( ctx.remoteAddress, queue, TextMessage.Strict( serializeJpssRegion(e._2))))
      jpssOverpasses.foreach( e=> pushTo( ctx.remoteAddress, queue, TextMessage.Strict( serializeJpssOverpassSeq(e._2))))
      jpssHotspots.foreach( e=> pushTo( ctx.remoteAddress, queue, TextMessage.Strict( serializeJpssHotspots(e._2))))
    }
  }

  override def receiveData: Receive = receiveJpssData.orElse(super.receiveData)

  // what we get from the bus (from import or replay actors)
  // NOTE this is executed in the data client thread
  def receiveJpssData: Receive = synchronized {
    case BusEvent(_,sat:OverpassRegion,_) =>
      jpssRegions += (sat.satId -> sat)
      if (hasConnections) push(TextMessage.Strict(serializeJpssRegion(sat)))

    case BusEvent(_,ops:OverpassSeq,_) =>
      jpssOverpasses += (ops.date -> ops)
      if (hasConnections) push( TextMessage.Strict(serializeJpssOverpassSeq(ops)))
      purgeOutdated()

    case BusEvent(_,hs:ViirsHotspots,_) =>
      jpssHotspots += (hs.date -> hs)
      if (hasConnections) push(TextMessage.Strict(serializeJpssHotspots(hs)))
      purgeOutdated()
  }

  def serializeJpssSatellites: String = {
    toNewJson( w=> w.writeObject(w=> w.writeArrayMember("jpssSatellites")(w=> jpssSatellites.foreach( _.serializeTo(w)))))
  }

  def serializeJpssRegion(sat: OverpassRegion): String = {
    toNewJson( w=> w.writeObject(w=> w.writeObjectMember("jpssRegion")( sat.serializeMembersTo)))
  }

  def serializeJpssOverpassSeq(ops: OverpassSeq): String = {
    toNewJson( w=> w.writeObject(w=> w.writeObjectMember("jpssOverpass")( ops.serializeMembersTo)))
  }

  def serializeJpssHotspots(hs: ViirsHotspots): String = {
    toNewJson( w=> w.writeObject(w=> w.writeObjectMember("jpssHotspots")( hs.serializeMembersTo)))
  }

  def serializeJpssDropHotspots (date: DateTime): String = {
    toNewJson( w=> w.writeObject(w=> w.writeDateTimeMember("jpssDropHotspots", date)))
  }

  def serializeJpssDropOverpass (date: DateTime): String = {
    toNewJson( w=> w.writeObject(w=> w.writeDateTimeMember("jpssDropOverpass", date)))
  }

  //--- cleanup

  def purgeOutdated(): Unit = {
    val cutoff = simClock.dateTime - hotspotMaxAge
    dropOldOverpasses(cutoff)
    dropOldHotspots(cutoff)
  }

  def dropOldHotspots(cutoff: DateTime): Unit = {
    jpssHotspots.foreach { e=>
      val hs = e._2
      if (hs.date < cutoff) {
        jpssHotspots -= e._1
        if (hasConnections) push( TextMessage.Strict(serializeJpssDropHotspots(hs.date)))
      }
    }
  }

  def dropOldOverpasses(cutoff: DateTime): Unit = {
    jpssOverpasses.foreach { e=>
      val ops = e._2
      if (ops.date < cutoff) {
        jpssOverpasses -= e._1
        if (hasConnections) push( TextMessage.Strict(serializeJpssDropOverpass(ops.date)))
      }
    }
  }
}

/**
  * simple service to show JPSS hotspots
  */
class CesiumJpssApp (val parent: ParentActor, val config: Config) extends DocumentRoute with CesiumJpssRoute
