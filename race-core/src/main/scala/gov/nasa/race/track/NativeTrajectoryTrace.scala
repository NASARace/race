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


/**
  * a trace that stores track points in original size
  */
class NativeTrajectoryTrace(val capacity: Int) extends TrajectoryTrace {

  protected val lats: Array[Double] = new Array[Double](capacity)
  protected val lons: Array[Double] = new Array[Double](capacity)
  protected val alts: Array[Double] = new Array[Double](capacity)
  protected val dates: Array[Long] = new Array[Long](capacity)

  override protected def setTrackPointData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double): Unit = {
    lats(idx) = lat
    lons(idx) = lon
    alts(idx) = alt
    dates(idx) = t
  }

  override protected def processTrackPointData(i: Int, idx: Int, f: (Int,Long,Double,Double,Double)=>Unit): Unit = {
    f(i, dates(idx), lats(idx),lons(idx),alts(idx))
  }
}
