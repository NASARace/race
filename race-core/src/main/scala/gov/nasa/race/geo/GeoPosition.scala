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
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._

object GeoPosition {
  def apply (φ: Angle, λ: Angle): GeoPosition = new LatLonPos(φ,λ)

  def fromDegrees (latDeg: Double, lonDeg: Double): GeoPosition = new LatLonPos( Degrees(latDeg), Degrees(lonDeg))
}

/**
  * abstract position
  */
trait GeoPosition {
  def φ: Angle
  @inline def lat = φ  // just an alias

  def λ: Angle
  @inline def lon = λ  // just an alias

  def altitude: Length = Length0  // override if we have one

  @inline def =:= (other: GeoPosition): Boolean = (φ =:= other.φ) && (λ =:= other.λ) && (altitude =:= other.altitude)

  @inline def isDefined = φ.isDefined && λ.isDefined && altitude.isDefined

  @inline def latDeg = φ.toDegrees
  @inline def lonDeg = λ.toDegrees
  @inline def altMeters: Double = altitude.toMeters

  def map (φ: Angle, λ: Angle, alt: Length = Length0): GeoPosition
}

/**
  * object that has a position
  */
trait GeoPositioned {
  def position: GeoPosition
}

trait GeoPositioned3D extends GeoPositioned {
  def altitude: Length
}

object LatLonPos {
  val zeroPos = new LatLonPos(Angle.Angle0, Angle.Angle0)
  val undefinedPos = new LatLonPos(Angle.UndefinedAngle, Angle.UndefinedAngle)

  def fromDegrees (φ: Double, λ: Double) = new LatLonPos( Degrees(φ), Degrees(λ))
}

/**
 * geographic position consisting of latitude and longitude
 *
 * @param φ latitude (positive north)
 * @param λ longitude (positive east)
 */
case class LatLonPos(val φ: Angle, val λ: Angle) extends GeoPosition {
  override def toString = {
    f"LatLonPos{φ=${φ.toDegrees}%+3.5f°,λ=${λ.toDegrees}%+3.5f°}"
  }

  override def map (φ: Angle, λ: Angle, alt: Length = Length0): GeoPosition = {
    new LatLonPos(φ,λ)
  }
}


object LatLonAltPos {
  val zeroPos = new LatLonAltPos(Angle.Angle0, Angle.Angle0, Length.Length0)
  val undefinedPos = new LatLonAltPos(Angle.UndefinedAngle, Angle.UndefinedAngle, Length.UndefinedLength)

  def fromDegreesAndFeet (φ: Double, λ: Double, alt: Double) = new LatLonAltPos( Degrees(φ), Degrees(λ), Feet(alt))
}
/**
  * geographic position with altitude
  *
  * TODO - should this be merged with LatLonPos? If so, how to separate between alt/elev? What to use
  * if there is no alt info (undefined or 0)?
  */
case class LatLonAltPos (val φ: Angle, val λ: Angle, override val altitude: Length) extends GeoPosition {
  override def toString = {
    f"LatLonAltPos{φ=${φ.toDegrees}%+3.5f°,λ=${λ.toDegrees}%+3.5f°,alt=${altitude.toMeters}m"
  }

  override def map (φ: Angle, λ: Angle, alt: Length = Length0): GeoPosition = {
    new LatLonAltPos(φ,λ,alt)
  }
}