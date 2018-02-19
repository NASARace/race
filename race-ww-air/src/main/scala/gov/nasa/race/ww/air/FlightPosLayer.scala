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

import java.awt.Color

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.{AirLocator, FlightPos}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.track.TrackTerminationMessage
import gov.nasa.race.ww._
import gov.nasa.race.ww.track.ModelTrackLayer

/**
 * a WorldWind layer to display FlightPos objects
 */
class FlightPosLayer (raceView: RaceView,config: Config)
                       extends ModelTrackLayer[FlightPos](raceView,config) with AirLocator {

  override def defaultSymbolColor = Color.red
  override def defaultSymbolImage = Images.getPlaneImage(color)

  override def getTrackKey(track: FlightPos): String = track.cs


  def handleFlightPosLayerMessage: Receive = {
    case BusEvent(_,fpos:FlightPos,_) =>
      incUpdateCount

      getTrackEntry(fpos) match {
        case Some(acEntry) =>
          if (fpos.isDroppedOrCompleted) removeTrackEntry(acEntry) else updateTrackEntry(acEntry, fpos)

        case None => addTrackEntry(fpos)
      }

    case BusEvent(_,msg: TrackTerminationMessage,_)  =>
      incUpdateCount
      ifSome(trackEntries.get(msg.cs)) {removeTrackEntry}
  }

  override def handleMessage = handleFlightPosLayerMessage orElse super.handleMessage
}
