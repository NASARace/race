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
import math.{atan2,asin,sqrt,cos,Pi}

/**
 * object with library functions to compute great circle trajectories using
 * the squants project (http://www.squants.com/) for dimensional analysis
 *
 * note this is just the spherical approximation, not WGS84 based
 */
object GreatCircle {

  /**
   * compute initial bearing for great circle between two given positions, using
   * equation:
   * {{{
   *   θ = atan2( sin Δλ ⋅ cos φ2 , cos φ1 ⋅ sin φ2 − sin φ1 ⋅ cos φ2 ⋅ cos Δλ )
   * }}}
   */
  def initialBearing(startPos: LatLonPos, endPos: LatLonPos): Angle = {
    val φ1 = startPos.φ
    val φ2 = endPos.φ
    val Δλ = endPos.λ - startPos.λ

    Radians(atan2(Sin(Δλ) * Cos(φ2), Cos(φ1) * Sin(φ2) - Sin(φ1) * Cos(φ2) * Cos(Δλ)))
  }

  /**
   * compute final bearing for great circle between two given positions, using
   * initialBearing of reverse route
   */
  def finalBearing(startPos: LatLonPos, endPos: LatLonPos): Angle = {
    Radians((initialBearing(endPos, startPos).toRadians + Pi) % TwoPi)
  }

  /**
    * compute great circle distance given start and endpoint, using haversine equations:
    * {{{
    *   a = sin²(Δφ/2) + cos φ1 ⋅ cos φ2 ⋅ sin²(Δλ/2)
    *   c = 2 ⋅ atan2( √a, √(1−a) )
    *   d = R ⋅ c
    * }}}
    * Note this is only an approximation since it assumes a spherical earth
    */
  def distance(startPos: LatLonPos, endPos: LatLonPos, alt: Length = Length0): Length = {
    val φ1 = startPos.φ
    val φ2 = endPos.φ
    val Δφ = φ2 - φ1
    val Δλ = endPos.λ - startPos.λ

    val a = Sin2(Δφ / 2) + Cos(φ1) * Cos(φ2) * Sin2(Δλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    (MeanEarthRadius + alt) * c
  }

  def distance2D (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Length = {
    val Δφ = φ2 - φ1
    val Δλ = λ2 - λ1

    val a = Sin2(Δφ / 2) + Cos(φ1) * Cos(φ2) * Sin2(Δλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    MeanEarthRadius * c
  }
  @inline def distance2D (startPos: LatLonPos, endPos: LatLonPos): Length = {
    distance2D(startPos.φ, startPos.λ, endPos.φ, endPos.λ)
  }

  /**
    * approximation for small distances, which is about 2-3 times faster than full haversine with errors ~1%
    */
  def euclidianDistance2D (φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Length = {
    val Δφ = φ2 - φ1
    val Δλ = λ2 - λ1

    val x = Δφ.toDegrees
    val y = Δλ.toDegrees * Cos(φ1)
    Meters(111320.0 * sqrt(x*x + y*y))  // 110250.0 ?
  }
  @inline def euclidianDistance2D (startPos: LatLonPos, endPos: LatLonPos): Length = {
    euclidianDistance2D(startPos.φ, startPos.λ, endPos.φ, endPos.λ)
  }


  def generateArcLonDeg = {
    println("val ArcLonDeg = Array[Double](")
    print("  ")
    for (deg <- 0 to 89) {
      val phi = deg*Pi / 180.0
      val arc = (Pi * RE_E * cos(phi)) / (180.0 * sqrt(1 - E2*sin2(phi)))

      print(arc)

      print(", ")
      if (deg > 0 && ((deg+1) % 5 == 0)) {
        println
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
  def endPos(startPos: LatLonPos, dist: Length, initialBearing: Angle, alt: Length = Meters(0)): LatLonPos = {
    val φ1 = startPos.φ
    val λ1 = startPos.λ
    val θ = initialBearing
    val δ = Radians(dist / (MeanEarthRadius + alt))

    val φ2 = Radians(asin(Sin(φ1) * Cos(δ) + Cos(φ1) * Sin(δ) * Cos(θ)))
    val λ2 = λ1 + Radians(atan2(Sin(θ) * Sin(δ) * Cos(φ1), Cos(δ) - Sin(φ1) * Sin(φ2)))

    LatLonPos(φ2, λ2)
  }

  def translate (pos: LatLonPos, startPos: LatLonPos, endPos: LatLonPos): LatLonPos = {
    val Δφ = endPos.φ - startPos.φ
    val Δλ = endPos.λ - startPos.λ
    LatLonPos(pos.φ + Δφ, pos.λ + Δλ)
  }
}

