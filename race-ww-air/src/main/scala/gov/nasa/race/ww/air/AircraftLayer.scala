/*
 * Copyright (c) 2016, United States Government, as represented by the
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
import gov.nasa.race.air.{AirLocator, TrackedAircraft, TrackedAircraftSeq}
import gov.nasa.race.core.BusEvent
import gov.nasa.race.track.TrackTerminationMessage
import gov.nasa.race.ww._
import gov.nasa.race.ww.track.ModelTrackLayer

/**
 * a WorldWind layer to display TrackedAircraft objects
 */
class AircraftLayer (val raceViewer: RaceViewer, val config: Config) extends ModelTrackLayer[TrackedAircraft] with AirLocator {

  override def defaultSymbolImg = Images.getPlaneImage(color)

  override def getTrackKey(track: TrackedAircraft): String = track.cs

  def handleAircraftLayerMessage: Receive = {
    case BusEvent(_,track:TrackedAircraft,_) => handleTrack(track)
    case BusEvent(_,tracks:TrackedAircraftSeq[_],_) => tracks.foreach(handleTrack)
    case BusEvent(_,msg: TrackTerminationMessage,_)  => handleTermination(msg)
  }

  override def handleMessage = handleAircraftLayerMessage orElse super.handleMessage
}
