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

/**
  * encode/decode lat lon in degrees to/from 64 bit preserving 6 decimal places, which gives us about 10cm accuracy
  * see http://www.dupuis.me/node/35
  *
  * TODO - find a better way to handle the abs(lon)=180.0 case (grid has a few values left that are not used)
  */
class WGS84Codec {
  final val eps = 0.00000000001
  final val frac = 10000000.0

  def encode (latDeg: Double, lonDeg: Double): Long = {
    // this takes care of the numeric anomaly at lonDeg = +- 180.0
    val lonDegʹ = if (lonDeg == 180.0) lonDeg - eps else if (lonDeg == -180.0) lonDeg + eps else lonDeg

    val dlon = 180.0 + (if (lonDegʹ < -180.0) lonDegʹ + 360.0 else if (lonDegʹ > 180.0) lonDegʹ - 360.0 else lonDegʹ)
    val dlat = 90.0 + (if (latDeg < -90.0) latDeg + 180.0 else if (latDeg > 90.0) latDeg - 180.0 else latDeg)

    val grid = dlat.toInt * 360 + dlon.toInt
    val ilon = ((dlon - dlon.toInt) * frac).toInt
    val ilat = ((dlat - dlat.toInt) * frac).toInt

    var e: Long = 0
    e =  ((ilat >> 16) & 0xff) ; e <<= 8    // 56
    e += ((ilon >> 16) & 0xff) ; e <<= 16   // 48
    e += (ilat & 0xffff)       ; e <<= 16   // 32
    e += (ilon & 0xffff)       ; e <<= 16   // 16
    e += (grid & 0xffff)                    // 0

    e
  }

  // store result without the need for allocation
  var latDeg: Double = 0
  var lonDeg: Double = 0

  def decode (e: Long) = {
    val grid = (e & 0xffff).toInt
    val ilon = ((e >> 16) & 0xffff) + (((e >> 48) & 0xff).toInt << 16)
    val ilat = ((e >> 32) & 0xffff) + (((e >> 56) & 0xff).toInt << 16)

    lonDeg = (grid % 360) + (ilon.toDouble / frac) - 180.0
    latDeg = (grid / 360) + (ilat.toDouble / frac) - 90.0
  }
}
