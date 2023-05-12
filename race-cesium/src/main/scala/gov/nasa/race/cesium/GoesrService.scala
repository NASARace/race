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
import gov.nasa.race.earth.GoesrHotspot.{N_GOOD, N_PROBABLE, N_TOTAL}
import gov.nasa.race.earth.Hotspot._
import gov.nasa.race.earth.{GoesrHotspot, GoesrHotspots, HotspotMap}
import gov.nasa.race.http.{ContinuousTimeRaceRoute, DocumentRoute, PushWSRaceRoute, WSContext}
import gov.nasa.race.space.SatelliteInfo
import gov.nasa.race.ui._
import gov.nasa.race.uom.Time
import gov.nasa.race.uom.Time._
import scalatags.Text

import java.net.InetSocketAddress
import scala.collection.mutable.ArrayDeque
import scala.concurrent.duration.DurationInt

object GoesrService {
  val jsModule = "ui_cesium_goesr.js"
  val icon = "geo-sat-icon.svg"

  val GOESR_DATASET = asc("goesrDataSet")
}
import gov.nasa.race.cesium.GoesrService._

/**
  * a Cesium RaceRouteInfo that uses a collection of geostationary and polar-orbiter satellites to detect
  * fire hotspots
  */
trait GoesrService extends CesiumRoute with ContinuousTimeRaceRoute with PushWSRaceRoute with RaceDataClient with JsonProducer {

  val goesrSatellites = config.getConfigArray("goes-r.satellites").map(c=> new SatelliteInfo(c))
  val goesrAssets = getSymbolicAssetMap("goesr.assets", config, Seq(("fire","fire.png")))

  // our data
  private var hotspots = ArrayDeque.empty[GoesrHotspots]
  private val maxHistory = Time.fromFiniteDuration( config.getFiniteDurationOrElse("max-history", 7.days))

  //--- route

  override def route: Route = uiCesiumGoesrRoute ~ super.route

  def uiCesiumGoesrRoute: Route = {
    get {
      pathPrefix( "goesr-asset" ~ Slash) { // mesh-models and images
        extractUnmatchedPath { p =>
          completeWithSymbolicAsset(p.toString, goesrAssets)
        }
      } ~
      fileAssetPath(jsModule) ~
      fileAssetPath(icon)
    }
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection( ctx, queue)
    initializeGoesrConnection( ctx, queue)
  }

  def initializeGoesrConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    synchronized {
      pushTo( ctx.remoteAddress, queue, TextMessage.Strict( serializeGoesrSatellites))
      hotspots.foreach { hs =>
        pushTo(ctx.remoteAddress, queue, TextMessage.Strict(serializeGoesrHotspots(hs)))
      }
    }
  }

  def serializeGoesrSatellites: String = {
    toNewJson { w=>
      w.writeObject { w=>
        w.writeArrayMember("goesrSatellites") { w=>
          goesrSatellites.foreach( _.serializeTo(w))
        }
      }
    }
  }

  def serializeGoesrHotspots (hs: GoesrHotspots): String = {
    toNewJson { w=>
      w.writeObject(_.writeObjectMember(GOESR_DATASET)(hs.serializeMembersTo))
    }
  }

  // notification from bus actor that we have new data
  override def receiveData: Receive = receiveGoesrData.orElse(super.receiveData)

  def receiveGoesrData: Receive = {
    case BusEvent(channel,hs:GoesrHotspots,sender) =>
      val cutOff = simClock.dateTime - maxHistory
      hotspots = hotspots.dropWhile( e=> e.date < cutOff)
      hotspots += hs
      hotspots.sortInPlaceWith( (a,b) => a.date < b.date)

      push( TextMessage.Strict(serializeGoesrHotspots(hs)))
  }

  //--- document content

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumGoesrResources

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri,remoteAddr) + uiGoesrConfig(requestUri,remoteAddr)
  }

  def uiCesiumGoesrResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule(jsModule))
  }

  def uiGoesrConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("goes-r")
    s"""export const goesr = {
  ${cesiumLayerConfig(cfg, "/fire/detection/GOES-R", "GOES-R ABI Fire / Hotspot Characterization")},
  maxMissingMin: ${cfg.getFiniteDurationOrElse("max-missing", 15.minutes).toMinutes},
  pixelLevel: '${cfg.getStringOrElse("pixel-level", "all")}',
  followLatest: ${cfg.getBooleanOrElse("follow-latest", true)},
  lockStep: ${cfg.getBooleanOrElse("lock-step", true)},
  pointSize: ${cfg.getIntOrElse("point-size", 5)},
  outlineWidth: ${cfg.getIntOrElse("outline-width", 1)},
  strongOutlineWidth: ${cfg.getIntOrElse("strong-outline-width", 2)},
  goodColor: ${cesiumColor(cfg, "good-color", "Red")},
  goodFillColor:  ${cesiumColor(cfg, "good-fill-color", "#FF000080")},
  goodOutlineColor:  ${cesiumColor(cfg, "good-outline-color", "Yellow")},
  probableColor:  ${cesiumColor(cfg, "probable-color", "OrangeRed")},
  probableFillColor:  ${cesiumColor(cfg, "good-fill-color", "#FF450080")},
  probableOutlineColor: ${cesiumColor(cfg, "probable-color", "Maroon")},
  otherColor:  ${cesiumColor(cfg, "other-color", "Orange")},
  otherFillColor:  ${cesiumColor(cfg, "other-fill-color", "#FFA50080")},
  cloudColor: ${cesiumColor(cfg, "cloud-color", "LightGray")},
  saturatedColor: ${cesiumColor(cfg, "saturated-color", "White")},
  missingColor: ${cesiumColor(cfg, "missing-color", "Blue")},
  pointDC: new Cesium.DistanceDisplayCondition( ${cfg.getIntOrElse("point-dist", 0)}, Number.MAX_VALUE),
  boundsDC: new Cesium.DistanceDisplayCondition( 0, ${cfg.getIntOrElse( "bounds-dist", 800000)}),
  zoomHeight: ${cfg.getIntOrElse("zoom-height", 100000)},
 };
 """
  }
}

class CesiumGoesrApp (val parent: ParentActor, val config: Config) extends DocumentRoute with ImageryLayerService with GoesrService