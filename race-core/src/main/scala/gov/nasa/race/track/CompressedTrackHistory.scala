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
package gov.nasa.race.track

import gov.nasa.race.geo.WGS84Codec
import gov.nasa.race.uom.Angle

/**
  * dynamic TrackedObject states stored as (lossy) fixed point values. Each state occupies 3 Long values
  */
trait CompressedTrackHistory extends TrackHistory {
  protected var data: Array[Long]  // provided by concrete type

  protected var posCodec = new WGS84Codec // needs to be our own object to avoid result allocation
  protected var t0Millis: Long = 0        // start time in epoch millis

  final def getRelTime (t: Long): Long = {
    if (size == 0) {
      t0Millis = t
      0
    } else {
      t - t0Millis
    }
  }

  protected def setTrackData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double, hdg: Double, spd: Double, vr: Double): Unit = {
    val j = idx * 3 // each data point occupies 3 Long slots

    val dtMillis = getRelTime(t)
    val latlon = posCodec.encode(lat,lon)
    val altCm = Math.round(alt * 100.0).toInt

    data(j) = latlon
    data(j+1) = (dtMillis << 32) | altCm

    // heading is stored 0..360 fixedpoint with two decimals in 2 bytes
    val h: Long = (Angle.normalizeDegrees(hdg) * 100).round & 0xffff

    // both speed and vr are stored as signed 3 byte fixedpoints with 4 decimals (-838..838 m/sec)
    val s: Long = if (spd >= 0) ((spd * 10000).round & 0x7fffff) else (((-spd * 10000).round & 0x7fffff) | 0x800000)
    val v: Long = if (vr >= 0) ((vr * 10000).round & 0x7fffff) else (((-vr * 10000).round & 0x7fffff) | 0x800000)

    data(j+2) = (h << 48) | (s << 24) | v
  }

  protected def processTrackData(i: Int, idx: Int, f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit = {
    val j = idx * 3 // each data point occupies 3 Long slots

    posCodec.decode(data(j))
    val w = data(j+1)
    val t = t0Millis + (w >> 32)
    val altMeters = (w & 0xffffffff).toInt / 100.0

    var l = data(j+2)
    var vrMs = (l & 0x7fffff)/10000.0
    if ((l & 0x800000) != 0) vrMs = -vrMs

    l >>= 24
    var spdMs = (l & 0x7fffff)/10000.0
    if ((l & 0x800000) != 0) spdMs = -spdMs

    l >>= 24
    val hdgDeg = (l & 0xffff)/100.0

    f(i, t, posCodec.latDeg, posCodec.lonDeg, altMeters, hdgDeg,spdMs,vrMs)
  }
}
