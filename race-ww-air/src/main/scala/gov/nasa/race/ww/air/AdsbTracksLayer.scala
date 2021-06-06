/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.ww.air

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.TrackedAircraft
import gov.nasa.race.air.actor.AdsbStation
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.{geo, _}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{DynamicSelectionPanel, IdAndNamePanel}
import gov.nasa.race.track.TrackTerminationMessage
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.{RaceViewer, _}
import gov.nasa.race.ww.track.ModelTrackLayer

import scala.collection.mutable.LinkedHashSet

/**
  * a TrackLayer for ADS-B tracks which can dynamically request stations as ChannelTopics
  */
class AdsbTracksLayer(val raceViewer: RaceViewer, val config: Config) extends ModelTrackLayer[TrackedAircraft]{

  val gotoAltitude = config.getLengthOrElse("goto-altitude", Feet(200000)) // feet above ground

  val stationList = LinkedHashSet[AdsbStation](AdsbStation.NoStation)
  var selStation: Option[String] = None

  val selPanel = new DynamicSelectionPanel[AdsbStation,IdAndNamePanel[AdsbStation]]("selected station",
    Seq(AdsbStation.NoStation), 10,
    new IdAndNamePanel[AdsbStation]( _.id, _.description.getOrElse("")), selectStation).styled()

  panel.contents.insert(1,selPanel) // note we can't do that in createLayerInfoPanel since it gets executed in our base ctor

  override def extraReadChannels = config.getOptionalStringList("read-station-from")

  override def queryLocation(id: String): Option[geo.GeoPositioned] = None

  def handleAdsbMessage: Receive = {
    case BusEvent(_, station: AdsbStation, _) =>
      if (station.isAvailable) {
        stationList += station
        selPanel.addItem(station)
      } else {
        stationList -= station
        selPanel.removeItem(station)
      }

    case BusEvent(_, track: TrackedAircraft, _) => if (selStation.isDefined) handleTrack(track)
    case BusEvent(_, msg: TrackTerminationMessage, _) => handleTermination(msg)
  }

  override def handleMessage = handleAdsbMessage orElse super.handleMessage

  def selectStation(station: AdsbStation): Unit = {
    ifSome(selStation) { id=>
      if (id != station.id){
        releaseTopic(selStation)
        clearTrackEntries
      }
    }

    if (station != AdsbStation.NoStation) {
      selStation = Some(station.id)
      requestTopic(selStation)
      ifSome(station.position) { pos =>
        raceViewer.panTo(wwPosition(pos), gotoAltitude.toMeters)
      }

    } else {
      selStation = None
    }
  }
}
