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
package gov.nasa.race.trajectory

import gov.nasa.race.geo.LatLonArray
import gov.nasa.race.uom.{Date, DateArray, LengthArray, TimeArray}
import gov.nasa.race.uom.Date._

/**
  * common type of compressed trajectories that store data in 32bit quantities.
  *
  * Time values are stored as offsets to the initial TrackPoint, which limits durations to <900h
  * Altitude is stored as Float meters (~7 digits give us cm accuracy for flight altitudes < 99,999m)
  * lat/lon encoding uses the algorithm from (see http://www.dupuis.me/node/35), which has ~10cm accuracy
  *
  * all indices are 0-based array offsets
  */
trait CompressedTraj extends Trajectory {
  protected[trajectory] var t0: Date = UndefinedDate

  protected[trajectory] var ts: TimeArray = new TimeArray(capacity)
  protected[trajectory] var latLons: LatLonArray = new LatLonArray(capacity)
  protected[trajectory] var alts: LengthArray = new LengthArray(capacity)


}

class CompressedTrajectory {

}
