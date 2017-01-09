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

package gov.nasa.race.geo

import gov.nasa.race.common._
import gov.nasa.race.uom.{Angle, Length}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._


/**
  * functions related to Datum conversion
  *
  * TODO - extend .uom package so that we don't have to convert to Doubles
  * TODO - we need a cartesian coordinate type (not yet clear if Vec3[T] or Vec4[T])
  */
object Datum {

  /**
    * convert earth-centered-inertial (ECI) geocentric (x,y,z) coordinates to
    * (lat,lon,alt) WGS84 geodetic coordinates
    *
    * from Astronomical Almanac pg. K12
    */
  def eciToWGS84 (xLen: Length, yLen: Length, zLen: Length): LatLonAltPos = {
    val x = xLen.toMeters
    val y = yLen.toMeters
    val z = zLen.toMeters

    val r = √(x*x + y*y)
    var φ1 = Math.atan(z/r)
    var φ = 0.0
    var c = 0.0

    do {
      φ = φ1
      val sin_φ = Math.sin(φ)
      c = √(1.0 - E_ECC* squared(sin_φ))
      φ1 = Math.atan((z + RE_E*E_ECC * c * sin_φ) / r)
    } while (Math.abs(φ - φ1) > 0.000000001)

    val h = r / Math.cos(φ) - RE_E*c
    val λ = Math.atan(y/x)

    LatLonAltPos(Radians(φ),Radians(λ),Meters(h))
  }
  @inline def eciToWGS84 (pos: Tuple3[Length,Length,Length]): LatLonAltPos = eciToWGS84(pos._1,pos._2,pos._3)

  /**
    * convert geodetic (lat,lon,alt) WGS84 coordinates into geocentric ECI (x,y,z) coordinates
    *
    * from Astronomical Almanac pg. K12
    */
  def wgs84ToECI (φ: Angle, λ: Angle, alt: Length): Tuple3[Length,Length,Length] = {
    val h = alt.toMeters
    val cos_φ = cos(φ)
    val sin_φ = sin(φ)

    val f = squared(1.0 - RE_FLATTENING)
    val c = √( squared(cos_φ) + f * squared(sin_φ))
    val s = f * c
    val ach = (RE_E * c + h)

    val x = ach * cos_φ * cos(λ)
    val y = ach * cos_φ * sin(λ)
    val z = (RE_E * s + h) * sin_φ

    (Meters(x),Meters(y),(Meters(z)))
  }
  @inline def wgs84ToECI (pos: LatLonAltPos): Tuple3[Length,Length,Length] = wgs84ToECI(pos.φ,pos.λ,pos.altitude)

  def main (args: Array[String]): Unit = {
    //val pos = LatLonAltPos(Degrees(37.415),Degrees(-122.048333),Meters(10000))
    val pos = LatLonAltPos(Degrees(-48.654090194270736),Degrees(321.67482079047466),Kilometers(425.1322400365634))

    println(s"start with $pos")
    val v = wgs84ToECI(pos)
    println(v)
    val p = eciToWGS84(v)
    println(p)
  }
}
