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

package gov.nasa.race.ww.layers

import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.core._
import gov.nasa.race.data.{FlightInfo, FlightPos, FlightTerminationMessage}
import gov.nasa.race.ww._

/**
 * a WorldWind layer to display FlightPos objects
 */
class FlightPosLayer (raceView: RaceView,config: Config) extends FlightLayer[FlightPos](raceView,config) {

  override def handleMessage = {
    case BusEvent(_,fpos:FlightPos,_) =>
      count = count + 1
      flights.get(fpos.cs) match {
        case Some(acEntry) => updateFlightEntry(acEntry,fpos)
        case None => addFlightEntry(fpos)
      }

    case BusEvent(_,msg: FlightTerminationMessage,_)  =>
      count = count + 1
      ifSome(flights.get(msg.cs)) {removeFlightEntry}

    case other => super.handleMessage(other)
  }
}
