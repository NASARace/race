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
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._
import math.{atan2,asin,acos,sqrt,sin,cos}

/**
 * object with library functions to compute great circle trajectories
 *
 * note this is just the spherical approximation of earth
 *
 * see http://www.movable-type.co.uk/scripts/latlong.html
 */
object GreatCircle {

  /**
    * compute initial bearing for great circle between two given positions, using
    * equation:
    * {{{
    *   θ = atan2( sin Δλ ⋅ cos φ2 , cos φ1 ⋅ sin φ2 − sin φ1 ⋅ cos φ2 ⋅ cos Δλ )
    * }}}
    */

  def initialBearing (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Angle = {
    val Δλ = λ2 - λ1
    val cos_φ2 = Cos(φ2)
    Radians( atan2( Sin(Δλ) * cos_φ2, Cos(φ1) * Sin(φ2) - Sin(φ1) * cos_φ2 * Cos(Δλ)) % π2)
  }
  @inline def initialBearing (startPos: GeoPosition, endPos: GeoPosition): Angle = {
    initialBearing(startPos.φ,startPos.λ,endPos.φ,endPos.λ)
  }

  /**
   * compute final bearing for great circle between two given positions, using
   * initialBearing of reverse route
   */
  @inline def finalBearing (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Angle = {
    Radians( (initialBearing(φ2,λ2, φ1,λ1).toRadians + π) % π2)
  }
  @inline def finalBearing(startPos: GeoPosition, endPos: GeoPosition): Angle = {
    finalBearing(startPos.φ,startPos.λ,endPos.φ,endPos.λ)
  }

  def angularDistance (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Angle = {
    val Δφ = φ2 - φ1
    val Δλ = λ2 - λ1

    val a = Sin2(Δφ / 2) + Cos(φ1) * Cos(φ2) * Sin2(Δλ / 2)
    Radians(2.0 * atan2(sqrt(a), sqrt(1.0 - a)))
  }
  @inline def angularDistance(startPos: GeoPosition, endPos: GeoPosition): Angle = {
    angularDistance(startPos.φ, startPos.λ, endPos.φ, endPos.λ)
  }

  /**
    * compute great circle distance given start and endpoint, using haversine equations:
    * {{{
    *   a = sin²(Δφ/2) + cos φ1 ⋅ cos φ2 ⋅ sin²(Δλ/2)
    *   c = 2 ⋅ atan2( √a, √(1−a) )
    *   d = R ⋅ c
    * }}}
    */
  def distance (φ1: Angle, λ1: Angle, alt1: Length, φ2: Angle, λ2: Angle, alt2: Length): Length = {
    val Δφ = φ2 - φ1
    val Δλ = λ2 - λ1

    val a = Sin2(Δφ / 2) + Cos(φ1) * Cos(φ2) * Sin2(Δλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    (MeanEarthRadius + (alt2 + alt1)/2.0) * c
  }

  @inline def distance(startPos: GeoPosition, endPos: GeoPosition): Length = {
    distance(startPos.φ, startPos.λ, startPos.altitude, endPos.φ, endPos.λ, endPos.altitude)
  }

  @inline def distance(startPos: GeoPosition, endPos: GeoPosition, alt: Length): Length = {
    distance(startPos.φ, startPos.λ, alt, endPos.φ, endPos.λ, alt)
  }

  def distance2D (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Length = {
    // save an addition and division
    val Δφ = φ2 - φ1
    val Δλ = λ2 - λ1
    val a = Sin2(Δφ / 2) + Cos(φ1) * Cos(φ2) * Sin2(Δλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    MeanEarthRadius * c
  }
  @inline def distance2D (startPos: GeoPosition, endPos: GeoPosition): Length = {
    distance2D(startPos.φ, startPos.λ, endPos.φ, endPos.λ)
  }

  def midPoint (pos1: GeoPosition, pos2: GeoPosition): GeoPosition = {
    val dist2 = distance(pos1,pos2) / 2.0
    val bearing = initialBearing(pos1,pos2)
    val alt = (pos1.altitude + pos2.altitude) / 2.0
    endPos(pos1,dist2,bearing,alt)
  }

  def generateArcLonDeg = {
    println("val ArcLonDeg = Array[Double](")
    print("  ")
    for (deg <- 0 to 89) {
      val phi = deg*π / 180.0
      val arc = (Pi * RE_E * cos(phi)) / (180.0 * sqrt(1 - E2*sin2(phi)))

      print(arc)

      print(", ")
      if (deg > 0 && ((deg+1) % 5 == 0)) {
        println()
        print("  ")
      }
    }
    println("0.0")  // 90 deg
    println(")")
  }

  /**
   * compute great circle end position for given start position, distance,
   * bearing and (optional) altitude, using equations:
   * {{{
   *   φ2 = asin( sin φ1 ⋅ cos δ + cos φ1 ⋅ sin δ ⋅ cos θ )
   *   λ2 = λ1 + atan2( sin θ ⋅ sin δ ⋅ cos φ1, cos δ − sin φ1 ⋅ sin φ2 )
   * }}}
   */
  def endPos(startPos: GeoPosition, dist: Length, initialBearing: Angle, alt: Length = Length0): GeoPosition = {
    val φ1 = startPos.φ
    val λ1 = startPos.λ
    val θ = if (dist.isPositive) initialBearing else initialBearing + Pi
    val δ = Radians( dist.abs / (MeanEarthRadius + alt))

    val sin_φ1 = Sin(φ1)
    val cos_δ = Cos(δ)
    val `cos_φ1×sin_δ` = Cos(φ1) * Sin(δ)

    val φ2 = Radians( asin( sin_φ1 * cos_δ + `cos_φ1×sin_δ` * Cos(θ)))
    val λ2 = λ1 + Radians( atan2( Sin(θ) * `cos_φ1×sin_δ`, cos_δ - sin_φ1 * Sin(φ2)))

    GeoPosition( φ2.toNormalizedLatitude, λ2.toNormalizedLongitude, alt)
  }

  def translate (pos: GeoPosition, startPos: GeoPosition, endPos: GeoPosition): GeoPosition = {
    val φ = pos.φ +  (endPos.φ - startPos.φ)
    val λ = pos.λ + (endPos.λ - startPos.λ)
    val h = pos.altitude + (endPos.altitude - startPos.altitude)
    GeoPosition(φ,λ,h)
  }

  def crossTrackDistance (φ: Angle, λ: Angle,
                          φ1: Angle, λ1: Angle, alt1: Length,
                          φ2: Angle, λ2: Angle, alt2: Length): Length = {

    val d13 = angularDistance( φ1,λ1, φ,λ).toRadians
    //val d13 = acos( Sin(φ1)*Sin(φ) + (Cos(φ1)*Cos(φ) * Cos(λ1-λ))) // angular distance between p1,p
    //val d13 = 2.0 * asin( sqrt(squared(  Sin((φ1-φ)/2)) + Cos(φ1)*Cos(φ) * squared(Sin((φ1-φ)/2))))

    val h13 = initialBearing(φ1,λ1, φ,λ)
    val h12 = initialBearing(φ1,λ1, φ2,λ2)

    asin( sin(d13) * Sin(h13 - h12)) * (MeanEarthRadius + (alt1 + alt2)/2.0)
  }
  @inline def crossTrackDistance(pos: GeoPosition, startPos: GeoPosition, endPos: GeoPosition): Length = {
    crossTrackDistance( pos.φ,pos.λ, startPos.φ,startPos.λ,startPos.altitude, endPos.φ,endPos.λ,endPos.altitude)
  }

  def alongTrackDistance (φ: Angle, λ: Angle,
                          φ1: Angle, λ1: Angle, alt1: Length,
                          φ2: Angle, λ2: Angle, alt2: Length): Length = {
    val r = MeanEarthRadius + (alt1 + alt2)/2

    //val d13 = acos( Sin(φ1)*Sin(φ) + (Cos(φ1)*Cos(φ) * Cos(λ1-λ))) // angular distance between p1,p
    val d13 = angularDistance( φ1,λ1, φ,λ).toRadians

    val h13 = initialBearing(φ1,λ1, φ,λ)
    val h12 = initialBearing(φ1,λ1, φ2,λ2)
    val dxt = asin( sin(d13) * Sin(h13 - h12)) * r // cross track distance

    acos( cos(d13) / cos(dxt/r)) * r
  }
  @inline def alongTrackDistance(pos: GeoPosition, startPos: GeoPosition, endPos: GeoPosition): Length = {
    alongTrackDistance( pos.φ,pos.λ, startPos.φ,startPos.λ,startPos.altitude, endPos.φ,endPos.λ,endPos.altitude)
  }

  /**
   * ortho projection of p onto GC pStart->pEnd
   * we roll this into one method to save expensive trig function calls
   */
  def crossTrackPoint (p: GeoPosition, pStart: GeoPosition, pEnd: GeoPosition): GeoPosition = {
    val r = MeanEarthRadius
    val φ = p.φ
    val λ = p.λ
    val φ1 = pStart.φ
    val λ1 = pStart.λ
    val φ2 = pEnd.φ
    val λ2 = pEnd.λ

    val sin_φ = Sin(φ)
    val cos_φ = Cos(φ)
    val cos_φ1 = Cos(φ1)
    val cos_φ2 = Cos(φ2)
    val sin_φ1 = Sin(φ1)
    val sin_φ2 = Sin(φ2)

    //val h12 = initialBearing(φ1,λ1, φ2,λ2)
    val Δλ = λ2 - λ1
    val h12 = Radians( atan2( Sin(Δλ) * cos_φ2, cos_φ1 * sin_φ2 - sin_φ1 * cos_φ2 * Cos(Δλ)) % π2)

    // compute along track distance
    //val h13 = initialBearing(φ1,λ1, φ,λ)
    val Δλ1 = λ - λ1
    val h13 = Radians( atan2( Sin(Δλ1) * cos_φ, cos_φ1 * sin_φ - sin_φ1 * cos_φ * Cos(Δλ1)) % π2)

    val d13 = acos( sin_φ1 * sin_φ + (cos_φ1*cos_φ * Cos(λ1-λ))) // angular distance between p1,p
    val dxt = asin( sin(d13) * Sin(h13 - h12)) * r
    val dist = acos(cos(d13)/cos(dxt/r)) * r

    // compute cross track point p4 (projecting p onto great circle p1-p2)
    val δ = Radians( dist.abs / r)
    val cos_δ = Cos(δ)
    val sin_δ = Sin(δ)
    val `cos_φ1×sin_δ` = cos_φ1 * sin_δ
    val φ4 = Radians( asin( sin_φ1 * cos_δ + `cos_φ1×sin_δ` * Cos(h12)))
    val λ4 = λ1 + Radians( atan2( Sin(h12) * `cos_φ1×sin_δ`, cos_δ - sin_φ1 * sin_φ2))

    GeoPosition( φ4.toNormalizedLatitude, λ4.toNormalizedLongitude)
  }

  /**
   * bearing from crosss track point to p
   */
  @inline def crossTrackBearing (p: GeoPosition, pStart: GeoPosition, pEnd: GeoPosition): Angle = {
    val p4 = crossTrackPoint(p, pStart, pEnd)
    initialBearing(p4, p)
  }
}

