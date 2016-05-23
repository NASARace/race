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
import gov.nasa.race.data.{FlightCompleted, FlightDropped, FlightPos}
import gov.nasa.race.ww._
import gov.nasa.worldwind.event.SelectEvent

/**
 * a WorldWind layer to display FlightPos objects
 */
class FlightPosLayer (raceView: RaceView,config: Config)
                                       extends InFlightAircraftLayer[FlightPos](raceView,config) {

  // this is called once we have a wwd and redrawManager
  override def initializeLayer() = {
    // set a select handler
    onSelected { e =>
      if (e.getEventAction == SelectEvent.LEFT_CLICK) {
        e.getTopObject match {
          case e: AircraftPlacemark[_] =>
            println(s"@@ ${e.t}")
          case other =>
        }
      }
    }
  }

  override def handleMessage = {
    case BusEvent(_,fpos:FlightPos,_) =>
      count = count + 1
      val key = fpos.cs
      aircraft.get(key) match {
        case Some(fposEntry) =>
          fposEntry.update(fpos)
        case None =>
          val fposEntry = new AircraftPlacemark[FlightPos](fpos,this)
          addRenderable(fposEntry)
          aircraft += (key -> fposEntry)
      }
      wwdRedrawManager.redraw()

    case BusEvent(_,msg: FlightCompleted,_) =>
      count = count + 1
      removeAircraft(msg.cs)

    case BusEvent(_,msg: FlightDropped,_) =>
      count = count + 1
      removeAircraft(msg.cs)

    case other => warning(f"$name ignoring message $other%30.30s..")
  }

  def removeAircraft (key: String) = {
    ifSome(aircraft.get(key)) { fpe =>
      removeRenderable(fpe)
      aircraft -= key
      wwdRedrawManager.redraw()
    }
  }
}
