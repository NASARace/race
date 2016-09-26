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

import math._
import gov.nasa.race.data._
import squants.space._

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

    Radians(normalize(atan2(sin(Δλ) * cos(φ2), cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ))))
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
   */
  def distance(startPos: LatLonPos, endPos: LatLonPos, alt: Length = Meters(0)): Length = {
    val φ1 = startPos.φ
    val φ2 = endPos.φ
    val Δφ = φ2 - φ1
    val Δλ = endPos.λ - startPos.λ

    val a = sin2(Δφ / 2) + cos(φ1) * cos(φ2) * sin2(Δλ / 2)
    val c = 2 * atan2(√(a), √(1 - a))

    (MeanEarthRadius + alt) * c
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

    val φ2 = Radians(asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ)))
    val λ2 = λ1 + Radians(atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2)))

    LatLonPos(φ2, λ2)
  }
}

