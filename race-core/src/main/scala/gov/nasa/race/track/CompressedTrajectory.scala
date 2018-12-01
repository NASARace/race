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

import gov.nasa.race.geo.WGS84Codec

/**
  * a trajectory that stores track points in compressed format
  *
  * each track point occupies two Long values. The first one encodes lat/lon, the second one alt/t
  * Note that we store the track point date (epoch in millis) as relative to the first entry, in 32bit,
  * which limits CompactTrajectories to about 1200h duration
  *
  * the data is kept in a single Array[Long] that has to be allocated/resized by the type that implements
  * this trait
  */
trait CompressedTrajectory extends Trajectory {
  protected var data: Array[Long]  // provided by concrete type

  protected var posCodec = new WGS84Codec // needs to be our own object to avoid result allocation
  protected var t0Millis: Long = 0        // start time in epoch millis

  protected def setTrackPointData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double): Unit = {
    val dtMillis = t - t0Millis
    val latlon = posCodec.encode(lat,lon)
    val altCm = Math.round(alt * 100.0).toInt

    data(idx) = latlon
    data(idx+1) = (dtMillis << 32) | altCm
  }

  protected def processTrackPointData(i: Int, idx: Int, f: (Int,Long,Double,Double,Double)=>Unit): Unit = {
    posCodec.decode(data(idx))
    val w = data(idx+1)
    val t = t0Millis + (w >> 32)
    val altMeters = (w & 0xffffffff).toInt / 100.0
    f(i, t, posCodec.latDeg, posCodec.lonDeg, altMeters)
  }
}
