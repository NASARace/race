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

package gov.nasa.race.air

import gov.nasa.race._
import gov.nasa.race.geo._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._

import Math._

/**
 * functions to convert cartesian ITWS grids into lat/lon
 * Based on Lincoln Labs Car92Projection
 *
 * <2do> verify signums (offsets and rotation)
 */
class ItwsGridProjection(val trpPos: GeoPosition, // tracon reference point in (φ,λ)
                         val xoffset: Length, yoffset: Length, // trp -> grid origin (SW)
                         val rotation: Angle) {                  // trueN -> magN at trp

  // rotation constants
  final val SinΘ = Sin(rotation)
  final val CosΘ = Cos(rotation)

  /**
   * transform cartesian grid coordinates into GeoPosition(φ,λ)
   *
   * @param xGrid  horizontal distance relative to grid origin (SW corner)
   * @param yGrid  vertical distance relative to grid origin (SW corner)
   * @return GeoPosition with latitude and longitude angles
   */
  def toLatLonPos (xGrid: Length, yGrid: Length): GeoPosition = {
    val x = xGrid + xoffset
    val y = yGrid + yoffset
    val xʹ = x * CosΘ - y * SinΘ
    val yʹ = x * SinΘ + y * CosΘ

    var rTrans = Length0
    var rMerid = Length0
    var φ = Angle0
    var φʹ = Angle0

    repeat(4) {
      φʹ = (trpPos.φ + φ) / 2
      val tmpECC = 1 - E_ECC * Sin2(φʹ)
      val sqrtECC = sqrt(tmpECC)
      rTrans = Meters(RE_E / sqrtECC)
      rMerid = Meters(RE_E * (1.0 - E_ECC) / (tmpECC * sqrtECC))
      φ = trpPos.φ + Radians( yʹ / rMerid)
    }

    val λ = trpPos.λ + Radians( xʹ / (rTrans * Cos((trpPos.φ + φ)/2)))
    GeoPosition(φ,λ)
  }

}
