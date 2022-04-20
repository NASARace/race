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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.BusEvent
import gov.nasa.race.http.{CachedFileAssetMap, PushWSRaceRoute, ResponseData}
import gov.nasa.race.land.Sentinel
import gov.nasa.race.ui.{extModule, uiIcon, uiList, uiWindow}
import scalatags.Text

import java.net.InetSocketAddress

object CesiumSentinelRoute extends CachedFileAssetMap {
  val sourcePath = "./race-cesium/src/main/resources/gov/nasa/race/cesium"
  val SENTINEL = asc("sentinel")
}
import gov.nasa.race.cesium.CesiumSentinelRoute._

trait CesiumSentinelRoute extends  CesiumRoute with PushWSRaceRoute {

  private val writer = new JsonWriter()

  val sentinelAssets = Map.from(config.getKeyValuePairsOrElse("sentinel-assets", Seq(("sentinel","diamond-point.png"))))

  def getSentinelAssetContent (key: String): Option[HttpEntity.Strict] = {
    sentinelAssets.get(key).map( fileName => getContent(fileName))
  }

  //--- websocket

  def serializeSentinel (channel: String, sentinel: Sentinel): Unit = {
    writer.clear().writeObject { w=>
      w.writeObjectMember(SENTINEL, sentinel)
    }
  }

  def receiveSentinelData: Receive = {
    case BusEvent(channel,sentinel: Sentinel,_) =>
      serializeSentinel(channel,sentinel)
      push(TextMessage.Strict(writer.toJson))
  }

  override def receiveData: Receive = receiveSentinelData.orElse(super.receiveData)

  //--- route

  def sentinelRoute: Route = {
    get {
      path("ui_cesium_sentinel.js") {
        complete( ResponseData.js( getContent("ui_cesium_sentinel.js")))
      } ~ path("sentinel-icon.svg") {
        complete( ResponseData.svg( getContent("sentinel-icon.svg")))
      } ~ pathPrefix( "sentinel-asset" ~ Slash) { // mesh-models and images
        extractUnmatchedPath { p =>
          getSentinelAssetContent(p.toString()) match {
            case Some(content) => complete(content)
            case None => complete(StatusCodes.NotFound, p.toString())
          }
        }
      }
    }
  }

  override def route: Route = sentinelRoute ~ super.route

  //--- document content

  def uiSentinelWindow(title: String="Sentinels"): Text.TypedTag[String] = {
    uiWindow(title, "sentinel")(
      uiList("sentinel.list", 10, "main.selectSentinel(event)"),
    )
  }

  def uiSentinelIcon: Text.TypedTag[String] = {
    uiIcon("sentinel-icon.svg", "main.toggleWindow(event,'sentinel')", "sentinel_icon")
  }

  def uiCesiumSentinelResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule("ui_cesium_sentinel.js"))
  }

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumSentinelResources

  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiSentinelWindow(), uiSentinelIcon)

  //--- config

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri,remoteAddr) + sentinelConfig(requestUri,remoteAddr)
  }

  def sentinelConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val labelOffsetX = config.getIntOrElse("sentinel-label-offset.x", 8)
    val labelOffsetY = config.getIntOrElse("sentinel-label-offset.y", 0)

    s"""
      export const sentinelColor = Cesium.Color.fromCssColorString('${config.getStringOrElse("sentinelColor", "chartreuse")}');
      export const sentinelLabelOffset = new Cesium.Cartesian2( $labelOffsetX, $labelOffsetY);
      export const sentinelBillboardDC = new Cesium.DistanceDisplayCondition( 0, 200000);
      """
  }
}
