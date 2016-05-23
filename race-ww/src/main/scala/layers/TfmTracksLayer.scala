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

import java.awt.Color

import com.typesafe.config.Config
import gov.nasa.race.core._
import gov.nasa.race.data.{TFMTrack, TFMTracks}
import gov.nasa.race.ww.RaceView
import gov.nasa.race.ww.AircraftPlacemark
import gov.nasa.worldwind.event.SelectEvent

/**
  * a RaceViewerActor WWJ layer to display TFM track data
  */
class TfmTracksLayer (raceView: RaceView,config: Config) extends InFlightAircraftLayer[TFMTrack](raceView,config) {

  override def defaultSymbolColor = Color.magenta

  override def initializeLayer() = {
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
    case BusEvent(_,msg: TFMTracks,_) =>
      count = count + 1
      msg.tracks foreach { t =>
        val key = t.cs
        aircraft.get(key) match {
          case Some(track) =>
            if (t.nextPos.isEmpty) { // completed flight
              removeRenderable(track)
              aircraft -= key
            } else {                 // updated flight
              track.update(t)
            }
          case None =>               // new flight
            if (!t.nextPos.isEmpty) { // but it might be completed
              val track = new AircraftPlacemark[TFMTrack](t, this)
              addRenderable(track)
              aircraft += (key -> track)
            }
        }
      }
      wwdRedrawManager.redraw()

    case other => warning(f"$name ignoring message $other%30.30s..")
  }
}