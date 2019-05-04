/*
 * Copyright (c) 2019, United States Government, as represented by the
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
import gov.nasa.race.uom.Angle.{Cos, Radians, Sin, π3_2, π_2}
import gov.nasa.race.uom.Area.√
import gov.nasa.race.uom.{Angle, Length}
import gov.nasa.race.uom.Length.Meters

import scala.math.sqrt

/**
  * a number of geospatial functions that use euclidean approximations for lat/lon/alt based computations
  * for performance reasons
  *
  * Use only for small differences in positions (difference to full haversine (GreatCircle) results is
  * <1m for distances <10nm)
  */
object Euclidean {
  def midPoint(pos1: GeoPosition, pos2: GeoPosition): GeoPosition = {
    val lat = (pos1.φ + pos2.φ) / 2.0
    val lon = (pos1.λ + pos2.λ) / 2.0
    val alt = (pos1.altitude + pos2.altitude) / 2.0
    GeoPosition(lat,lon,alt)
  }

  /**
    * approximation for small distances, which is about 2-3 times faster than full haversine with errors ~1%
    */
  def distance2D(φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Length = {
    val Δφ = φ2 - φ1
    val Δλ = λ2 - λ1

    val x = Δφ.toDegrees
    val y = Δλ.toDegrees * Cos(φ1)
    Meters(111320.0 * sqrt(x*x + y*y))  // 110250.0 ?
  }
  @inline def distance2D(startPos: GeoPosition, endPos: GeoPosition): Length = {
    distance2D(startPos.φ, startPos.λ, endPos.φ, endPos.λ)
  }

  /**
    * euclidean distance that avoids transient allocation of LatLonPos objects
    * Note this is an approximation since we only use the mean earth radius
    */
  def distance(φ1: Angle, λ1: Angle, alt1: Length, φ2: Angle, λ2: Angle, alt2: Length): Length = {
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
  @inline def distance(pos1: GeoPosition, pos2: GeoPosition): Length = {
    distance(pos1.φ,pos1.λ,pos1.altitude, pos2.φ,pos2.λ,pos2.altitude)
  }

  def heading(φ1: Angle, λ1: Angle, φ2: Angle, λ2: Angle): Angle = {
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
  @inline def heading(pos1: GeoPosition, pos2: GeoPosition): Angle = {
    heading(pos1.φ,pos1.λ, pos2.φ,pos2.λ)
  }

  /**
    * approximation of cross-track distance for small lat/lon deltas
    */
  def crossTrackDistance (φ: Angle, λ: Angle,
                          φ1: Angle, λ1: Angle, alt1: Length,
                          φ2: Angle, λ2: Angle, alt2: Length): Length = {
    val aAvg = ((alt1 + alt2) / 2).toMeters
    val cy = (MeanEarthRadius.toMeters + aAvg)
    val cx = cy / Cos(φ2 - φ1)

    val y1 = φ1.toRadians * cy
    val y2 = φ2.toRadians * cy
    val y = φ.toRadians * cy

    val x1 = λ1.toRadians * cx
    val x2 = λ2.toRadians * cx
    val x = λ.toRadians * cx

    Meters(((y2 - y1)*x - (x2 - x1)*y + x2*y1 - y2*x1) / sqrt( squared(y2-y1) + squared(x2-x1)))
  }
  @inline def crossTrackDistance(pos: GeoPosition, startPos: GeoPosition, endPos: GeoPosition): Length = {
    crossTrackDistance( pos.φ,pos.λ, startPos.φ,startPos.λ,startPos.altitude, endPos.φ,endPos.λ,endPos.altitude)
  }

  def alongTrackDistance (φ: Angle, λ: Angle,
                          φ1: Angle, λ1: Angle, alt1: Length,
                          φ2: Angle, λ2: Angle, alt2: Length): Length = {
    val aAvg = (alt1 + alt2)/2
    val dxt = crossTrackDistance(φ,λ, φ1,λ1,alt1, φ2,λ2,alt2).toMeters
    val dp = distance(φ1,λ1,aAvg, φ,λ,aAvg).toMeters

    Meters( sqrt( squared(dp) - squared(dxt) ))
  }

  @inline def alongTrackDistance(pos: GeoPosition, startPos: GeoPosition, endPos: GeoPosition): Length = {
    alongTrackDistance( pos.φ,pos.λ, startPos.φ,startPos.λ,startPos.altitude, endPos.φ,endPos.λ,endPos.altitude)
  }
}
