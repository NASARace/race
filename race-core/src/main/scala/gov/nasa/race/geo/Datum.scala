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
import gov.nasa.race.uom.Area._

import Math._
import scala.language.postfixOps

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
  def ecefToWGS84(xLen: Length, yLen: Length, zLen: Length): GeoPosition = {
    val x = xLen.toMeters
    val y = yLen.toMeters
    val z = zLen.toMeters

    val r = sqrt(x*x + y*y)
    var φ1 = atan(z/r)
    var φ = 0.0
    var c = 0.0

    do {
      φ = φ1
      val sin_φ = sin(φ)
      c = sqrt(1.0 - E_ECC* sin_φ`²`)
      φ1 = atan((z + RE_E*E_ECC * c * sin_φ) / r)
    } while (abs(φ - φ1) > 0.000000001)

    val h = r / cos(φ) - RE_E*c
    val λ = atan(y/x)

    GeoPosition(Radians(φ),Radians(λ),Meters(h))
  }
  @inline def ecefToWGS84(pos: (Length,Length,Length)): GeoPosition = ecefToWGS84(pos._1,pos._2,pos._3)
  @inline def ecefToWGS84(pos: XyzPos): GeoPosition = ecefToWGS84(pos.x,pos.y,pos.z)


  /**
    * convert geodetic (lat,lon,alt) WGS84 coordinates into geocentric ECI (x,y,z) coordinates
    *
    * from Astronomical Almanac pg. K12
    */
  def withECEF[U] (φ: Angle, λ: Angle, alt: Length)(fn: (Length,Length,Length)=>U): U = {
    val h = alt.toMeters
    val cos_φ = Cos(φ)
    val sin_φ = Sin(φ)

    val f = (1.0 - RE_FLATTENING)`²`
    val c = sqrt( (cos_φ`²`) + f * (sin_φ`²`))
    val s = f * c
    val ach = (RE_E * c + h)

    val x = ach * cos_φ * Cos(λ)
    val y = ach * cos_φ * Sin(λ)
    val z = (RE_E * s + h) * sin_φ

    fn( Meters(x),Meters(y),Meters(z))
  }
  @inline def withECEF(pos: GeoPosition)(f: (Length,Length,Length)=>Unit): Unit = {
    withECEF(pos.φ,pos.λ,pos.altitude)(f)
  }

  def wgs84ToECEF(φ: Angle, λ: Angle, alt: Length): XyzPos = {
    withECEF(φ, λ, alt)( (x,y,z) => XyzPos(x,y,z))
  }
  @inline def wgs84ToECEF(pos: GeoPosition): XyzPos = wgs84ToECEF(pos.φ,pos.λ,pos.altitude)


  def earthRadius (φ: Angle): Length = {
    val cos_φ = Cos(φ)
    val sin_φ = Sin(φ)

    Meters(sqrt(((RE_E2 * cos_φ).`²` + (RE_N2 * sin_φ).`²`)/(RE_E * cos_φ).`²` + (RE_N * sin_φ).`²`))
  }
}
