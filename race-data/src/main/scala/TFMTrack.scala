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

package gov.nasa.race.data

import com.github.nscala_time.time.Imports._
import gov.nasa.race.common.Dated
import squants.motion.Velocity
import squants.space.{Degrees, Length}

case class TFMTracks (tracks: Seq[TFMTrack]) {
  override def toString = {
    val sb = new StringBuilder
    sb.append("TFMTracks = [")
    tracks.foreach( t => { sb.append("\n"); sb.append(t) } )
    sb.append(" ]")
    sb.toString()
  }
}

/**
  * object representing a Traffic Flow Management (TFM) track
  * This represents flights that are tracked from ground stations (radar)
  *
  * While this is (currently) largely redundant with FlightPos (which is ADS-B/FIXM based), we
  * keep it as a separate type that is not coupled through a common base class
  *
  * <2do> this should not try to encode completed flights into values, separate into different object
  */
case class TFMTrack(flightId: String,
                    cs: String,
                    position: LatLonPos,
                    altitude: Length,
                    speed: Velocity,
                    source: String,
                    date: DateTime,
                    nextPos: Option[LatLonPos],
                    nextDate: Option[DateTime]
                   ) extends Dated with InFlightAircraft {

  val heading = if (nextPos.isDefined) GreatCircle.initialBearing(position,nextPos.get) else Degrees(0)
}
