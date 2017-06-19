/*
 * Copyright (c) 2017, United States Government, as represented by the
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
import gov.nasa.race.air.TATrack.Status
import gov.nasa.race.air.{TATrack, Tracon}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.ifSome
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.ww.RaceView


/**
  * a layer to display TRACONs and related TATracks
  */
class TATracksLayer (raceView: RaceView,config: Config) extends FlightLayer3D[TATrack](raceView,config){

  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 1250000d)) // feet above ground
  var selTracon: Option[Tracon] = None

  override def createLayerInfoPanel = {
    new FlightLayerInfoPanel(raceView,this){
      // insert tracon selection panel after generic layer info
      contents.insert(1, new StaticSelectionPanel[Tracon,IdAndNamePanel[Tracon]]("select TRACON", Tracon.traconList, 40,
                                            new IdAndNamePanel[Tracon]( _.id, _.name), selectTracon).styled())
    }.styled('consolePanel)
  }

  /**
    * this has to be implemented in the concrete RaceLayer. It is executed in the
    * event dispatcher
    */
  override def handleMessage: Receive = {
    case BusEvent(_, track: TATrack, _) =>
      selTracon match {
        case Some(tracon) =>
          if (track.src == tracon.id) {
            count = count + 1
            if (track.status != Status.Drop) {
              flights.get(track.cs) match {
                case Some(acEntry) => updateFlightEntry(acEntry, track)
                case None => addFlightEntry(track)
              }
            } else {
              ifSome(flights.get(track.cs)) {
                removeFlightEntry
              }
            }
          }
        case None => // nothing selected, ignore
      }
  }

  def selectTracon(tracon: Tracon) = raceView.trackUserAction(gotoTracon(tracon))

  def reset(): Unit = {
    removeAllRenderables()
    flights.clear()
    releaseAll
  }

  def gotoTracon(tracon: Tracon) = {
    selTracon match {
      case Some(`tracon`) => // nothing, we already show it
      case Some(lastTracon) =>
        reset
        selTracon = Some(tracon)
        requestTopic(selTracon)
      case None =>
        selTracon = Some(tracon)
        requestTopic(selTracon)
    }
    val alt = gotoAltitude.toMeters
    raceView.panTo(tracon.pos,alt)
  }

}
