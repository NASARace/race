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

import gov.nasa.race.Dated
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._
import gov.nasa.race.util.StringUtils

object GeoPosition {
  val zeroPos = new LatLonPos(Angle0, Angle0, Length0)
  val undefinedPos = new LatLonPos(UndefinedAngle, UndefinedAngle, UndefinedLength)

  def apply (φ: Angle, λ: Angle, alt: Length = Length0): GeoPosition = new LatLonPos(φ,λ,alt)

  def fromDegrees (latDeg: Double, lonDeg: Double): GeoPosition = new LatLonPos( Degrees(latDeg), Degrees(lonDeg), Length0)

  def fromDegreesAndMeters (latDeg: Double, lonDeg: Double, altMeters: Double) = {
    new LatLonPos(Degrees(latDeg), Degrees(lonDeg), Meters(altMeters))
  }
  def fromDegreesAndFeet (latDeg: Double, lonDeg: Double, altFeet: Double) = {
    new LatLonPos(Degrees(latDeg), Degrees(lonDeg), Feet(altFeet))
  }

  def fromRadiansAndMeters (lat: Double, lon: Double, altMeters: Double): LatLonPos = {
    new LatLonPos( Radians(lat), Radians(lon), Meters(altMeters))
  }

  val LAT = asc("lat")
  val LON = asc("lon")
  val ALT = asc("alt")

}
import GeoPosition._

/**
  * abstract position
  */
trait GeoPosition extends JsonSerializable {
  def φ: Angle
  @inline final def lat = φ  // just an alias

  def λ: Angle
  @inline final def lon = λ  // just an alias

  def altitude: Length
  def hasDefinedAltitude: Boolean = altitude.isDefined

  def normalized: GeoPosition = {
    if (lat.within( AngleNeg90, Angle90) && lon.within( AngleNeg180, Angle180)) this
    else GeoPosition( lat.toNormalizedLatitude, lon.toNormalizedLongitude, altitude)
  }

  @inline def =:= (other: GeoPosition): Boolean = (φ =:= other.φ) && (λ =:= other.λ) && (altitude =:= other.altitude)

  @inline final def isDefined = φ.isDefined && λ.isDefined && altitude.isDefined

  @inline final def latDeg = φ.toDegrees
  @inline final def lonDeg = λ.toDegrees
  @inline final def altMeters: Double = altitude.toMeters
  @inline final def altFeet: Int = altitude.toFeet.toInt

  // this assumes normalized angles (boundaries count as both)
  @inline final def isWest: Boolean = λ <= Angle0 && λ >= AngleNeg180
  @inline final def isEast: Boolean = λ >= Angle0 && λ <= Angle180
  @inline final def isNorth: Boolean = φ >= Angle0 && φ <= AngleNeg90
  @inline final def isSouth: Boolean = φ <= Angle0 && φ >= AngleNeg90

  override def toString: String = f"${getClass.getSimpleName}(φ=${φ.toDegrees}%+3.6f°,λ=${λ.toDegrees}%+3.6f°,alt=${altitude.toMeters}%.1fm)"
  def toGenericString3D: String = f"φ=${φ.toDegrees}%+3.6f°,λ=${λ.toDegrees}%+3.6f°,alt=${altitude.toMeters}%.1fm"
  def toGenericString2D: String = f"(φ=${φ.toDegrees}%+3.6f°,λ=${λ.toDegrees}%+3.6f°)"

  def latLon_5: String = f"${φ.toDegrees}%.5f,${λ.toDegrees}%.5f"

  // decimals = [0..6]
  def toLatLonString (decimals: Int): String = {
    val sb = new StringBuffer(32)
    val fmt = StringUtils.decimalFormatters(decimals)
    fmt.format(latDeg,sb)
    sb.append(',')
    fmt.format(lonDeg,sb)
    sb.toString
  }

  def toLatLonPos: LatLonPos = LatLonPos(φ, λ, altitude)
  def toMutLatLonPos: MutLatLonPos = MutLatLonPos( φ, λ, altitude)
  def toDegreesAndMeters: (Double,Double,Double) = (φ.toDegrees, λ.toDegrees, altitude.toMeters)

  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDoubleMember(LAT, lat.toDegrees)
    w.writeDoubleMember(LON, lon.toDegrees)
    w.writeDoubleMember(ALT, altitude.toMeters)
  }

  def to2d: GeoPosition = GeoPosition(φ,λ)
}


/**
  * a GeoPosition with a timestamp
  */
trait DatedGeoPosition extends Dated with GeoPosition with JsonSerializable {
  override def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember("date", date)
    super.serializeMembersTo(w)
  }
}

/**
  * object that has a position
  */
trait GeoPositioned {
  def position: GeoPosition
}


/**
 * invariant geographic position consisting of latitude and longitude
 *
 * @param φ latitude (positive north)
 * @param λ longitude (positive east)
 */
case class LatLonPos(val φ: Angle, val λ: Angle, val altitude: Length) extends GeoPosition {
  override def toLatLonPos: LatLonPos = this // no need to create a new one
}

/**
  * a GeoPosition that can be mutated
  * (e.g. to iterate through a large number of positions without allocation)
  */
case class MutLatLonPos (var φ: Angle = Angle0, var λ: Angle = Angle0, var altitude: Length = Length0) extends GeoPosition {
  def update (lat: Angle, lon: Angle, alt: Length): Unit = {
    φ = lat
    λ = lon
    altitude = alt
  }

  def update2D (lat: Angle, lon: Angle): Unit = {
    φ = lat
    λ = lon
  }
}

/**
 * Dimension-less version. You are on your own tracking them
 */
case class LatLonAlt (φ: Double, λ: Double, altitude: Double) {
  def asGeoPositionFromRadiansAndMeters: GeoPosition = GeoPosition.fromRadiansAndMeters(φ,λ,altitude)
  def asGeoPositionFromDegreesAndMeters: GeoPosition = GeoPosition.fromDegreesAndMeters(φ,λ,altitude)
}

/**
 * mutable version
 */
case class MutLatLonAlt (var φ: Double =0, var λ: Double =0, var altitude: Double =0) {
  def asGeoPositionFromRadiansAndMeters: GeoPosition = GeoPosition.fromRadiansAndMeters(φ,λ,altitude)
  def asGeoPositionFromDegreesAndMeters: GeoPosition = GeoPosition.fromDegreesAndMeters(φ,λ,altitude)
}