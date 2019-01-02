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



  /**
    * euclidean distance that avoids transient allocation of LatLonPos objects
    * Note this is an approximation since we only use the mean earth radius
    */
  def meanEuclideanDistance (φ1: Angle, λ1: Angle, alt1: Length, φ2: Angle, λ2: Angle, alt2: Length): Length = {
    val r1 = MeanEarthRadius + alt1
    val r1_cos_φ1 = r1 * Cos(φ1)
    val x1 = r1_cos_φ1 * Cos(λ1)
    val y1 = r1_cos_φ1 * Sin(λ1)
    val z1 = r1 * Sin(φ1)

    val r2 = MeanEarthRadius + alt2
    val r2_cos_φ2 = r2 * Cos(φ2)
    val x2 = r2_cos_φ2 * Cos(λ2)
    val y2 = r2_cos_φ2 * Sin(λ2)
    val z2 = r2 * Sin(φ2)

    √((x1-x2).`²` + (y1-y2).`²` + (z1-z2).`²` )
  }
  def meanEuclideanDistance (pos1: GeoPosition, pos2: GeoPosition): Length = {
    meanEuclideanDistance(pos1.φ,pos1.λ,pos1.altitude, pos2.φ,pos2.λ,pos2.altitude)
  }


  def meanEuclidean2dDistance (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Length = {
    val r = MeanEarthRadius

    val r_cos_φ1 = r * Cos(φ1)
    val x1 = r_cos_φ1 * Cos(λ1)
    val y1 = r_cos_φ1 * Sin(λ1)
    val z1 = r * Sin(φ1)


    val r_cos_φ2 = r * Cos(φ2)
    val x2 = r_cos_φ2 * Cos(λ2)
    val y2 = r_cos_φ2 * Sin(λ2)
    val z2 = r * Sin(φ2)


    √((x1-x2).`²` + (y1-y2).`²` + (z1-z2).`²` )
  }
  def meanEuclidean2dDistance (pos1: GeoPosition, pos2: GeoPosition): Length = meanEuclidean2dDistance(pos1.φ,pos1.λ, pos2.φ,pos2.λ)


  def euclideanHeading (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Angle = {
    val dx = λ2.toRadians - λ1.toRadians
    val dy = φ2.toRadians - φ1.toRadians

    if (dx == 0) { // singularities
      if (dy > 0)   Angle.Angle0
      else          Angle.Angle180

    } else {
      val a = Math.atan(dy/dx)
      if (a < 0){
        if (dy > 0) Radians(π3_2 - a) else Radians(π_2 - a)
      } else {
        if (dy > 0) Radians(π_2 - a) else Radians(π3_2 - a)
      }
    }
  }
  def euclideanHeading (pos1: GeoPosition, pos2: GeoPosition): Angle = euclideanHeading(pos1.φ,pos1.λ, pos2.φ,pos2.λ)

  def earthRadius (φ: Angle): Length = {
    val cos_φ = Cos(φ)
    val sin_φ = Sin(φ)

    Meters(sqrt(((RE_E2 * cos_φ).`²` + (RE_N2 * sin_φ).`²`)/(RE_E * cos_φ).`²` + (RE_N * sin_φ).`²`))
  }
}
