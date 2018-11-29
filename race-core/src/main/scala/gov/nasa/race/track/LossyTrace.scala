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
  * a trace with a compact (lossy) track point encoding
  */
class LossyTrace(val capacity: Int) extends Trace with LossyTraj {

  override protected var data: Array[Long] = new Array[Long](capacity*2)

  override protected def setTrackPointData(idx: Int, lat: Double, lon: Double, alt: Double, t: Long): Unit = {
    val dtMillis = t - t0Millis
    val latlon = posCodec.encode(lat,lon)
    val altCm = Math.round(alt * 100.0).toInt

    val j = idx*2
    data(j) = latlon
    data(j+1) = (dtMillis << 32) | altCm
  }

  override protected def processTrackPointData(i: Int, idx: Int, f: (Int,Double,Double,Double,Long)=>Unit): Unit = {
    val j = idx*2
    posCodec.decode(data(j))
    val w = data(j+1)
    val t = t0Millis + (w >> 32)
    val altMeters = (w & 0xffffffff).toInt / 100.0
    f(i, posCodec.latDeg, posCodec.lonDeg, altMeters, t)
  }
}
