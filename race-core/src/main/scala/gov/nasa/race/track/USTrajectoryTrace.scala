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
package gov.nasa.race.track

import gov.nasa.race.common.Nat.N3
import gov.nasa.race.common.{TDataPoint3, TDataSource}

object USTrajectoryTrace {

  // geo center of contiguous US
  val USCenterLat = 39.833333
  val USCenterLon = -98.583333

}

/**
  * a memory optimized trajectory trace for time-limited flights within the continental US.
  *
  * Uses 16 bytes per track point but does not require en/decoding
  *
  * trajectories can not extend 900h duration, position is accurate within +- 1m (still better
  * than most GPS)
  */
class USTrajectoryTrace (val capacity: Int) extends TrajectoryTrace with TDataSource[N3,TDataPoint3] {
  import USTrajectoryTrace._

  var t0Millis: Long = -1 // start time of trajectory in epoch millis (set when entering first point)

  val ts: Array[Int] = new Array(capacity)
  val dlats: Array[Float] = new Array(capacity)
  val dlons: Array[Float] = new Array(capacity)
  val alts: Array[Float] = new Array(capacity)

  override protected def setTrackPointData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double): Unit = {
    if (t0Millis < 0) {
      t0Millis = t
      ts(idx) = 0
    } else {
      ts(idx) = (t - t0Millis).toInt
    }

    dlats(idx) = (lat - USCenterLat).toFloat
    dlons(idx) = (lon - USCenterLon).toFloat
    alts(idx) = alt.toFloat
  }

  override protected def processTrackPointData(i: Int, idx: Int, f: (Int, Long, Double, Double, Double) => Unit): Unit = {
    f(i, ts(idx) + t0Millis, dlats(idx) + USCenterLat, dlons(idx) + USCenterLon, alts(idx).toDouble)
  }

  //--- the TDataSource interface for interpolation

  override def getTime(i: Int): Long = ts((tail+i)%capacity) + t0Millis

  override def newDataPoint: TDataPoint3 = new TDataPoint3(0,0,0,0)

  override def getDataPoint(i: Int, dp: TDataPoint3): Unit = {
    val j = (tail+i)%capacity
    dp.setTime(ts(j))
    dp._0 = dlats(j) + USCenterLat
    dp._1 = dlons(j) + USCenterLon
    dp._2 = alts(j)
  }


  /**
    * return a new Trajectory object that preserves the current state
    */
  override def snapshot: Trajectory = ???

  /**
    * return a new Trajectory object that can be modified
    * use this for branching trajectories
    */
  override def branch: ModifiableTrajectory = ???
}
