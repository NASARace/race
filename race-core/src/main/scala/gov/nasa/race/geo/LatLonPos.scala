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

import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom._

object LatLonPos {
  val zeroLatLonPos = new LatLonPos(Angle.Angle0,Angle.Angle0)
  val undefinedLatLonPos = new LatLonPos(Angle.UndefinedAngle,Angle.UndefinedAngle)

  def fromDegrees (φ: Double, λ: Double) = new LatLonPos( Degrees(φ), Degrees(λ))
}

/**
 * geographic position consisting of latitude and longitude
 *
 * @param φ latitude (positive north)
 * @param λ longitude (positive east)
 */
case class LatLonPos (val φ: Angle, val λ: Angle) {
  override def toString = {
    f"LatLonPos{φ=${φ.toDegrees}%+3.5f°,λ=${λ.toDegrees}%+3.5f°}"
  }

  @inline def =:= (other: LatLonPos): Boolean = (φ =:= other.φ) && (λ =:= other.λ)

  @inline def isDefined = φ.isDefined && λ.isDefined

  @inline def latDeg = φ.toDegrees
  @inline def lonDeg = λ.toDegrees
}

/**
  * geographic position with altitude
  *
  * TODO - should this extend LatLonPos? If so, we have to turn the former into a trait
  *
  * @param φ latitude (positive north)
  * @param λ longitude (positive east)
  * @param altitude from center of earth
  */
case class LatLonAltPos (val φ: Angle, val λ: Angle, val altitude: Length) {
  override def toString = {
    f"LatLonPos{φ=${φ.toDegrees}%+3.3f°,λ=${λ.toDegrees}%+3.3f°,alt=${altitude.toMeters}m"
  }

  @inline def =:= (other: LatLonAltPos): Boolean = (φ =:= other.φ) && (λ =:= other.λ) && (altitude =:= other.altitude)

  @inline def isDefined = φ.isDefined && λ.isDefined && altitude.isDefined

  @inline def latDeg = φ.toDegrees
  @inline def lonDeg = λ.toDegrees
}