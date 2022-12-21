/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.space

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.{Dated, ifSome}
import gov.nasa.race.common.{FMT_3_5, JsonSerializable, JsonWriter, MutXyz, squareRoot, squared}
import gov.nasa.race.geo.{GeoPosition, GreatCircle, MeanEarthRadius}
import gov.nasa.race.trajectory.{MutTrajectoryPoint, Trajectory}
import gov.nasa.race.uom.Angle.{Asin, Cos, Sin}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.{Angle, DateTime, Length, Time}
import org.orekit.propagation.SpacecraftState


/**
 * the invariant data of a monitored satellite
 *
 * satId is the NORAD cat id, i.e. it is a unique identifier
 * region is a geospatial polygon in which we monitor overpasses of this satellite
 */
case class OverpassRegion(satId: Int, satName: Option[String], region: Seq[GeoPosition]) extends JsonSerializable {

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer.writeIntMember("satId", satId)
    satName.foreach( writer.writeStringMember("name", _))
    writer.writeGeoPositionArrayMember("region", region)
  }
}

/**
 * overpass information of satellite with respect to a single fixed ground position
 * elev is elevation angle from ground pos (above horizon)
 * scan is instrument scan angle from satellite (from nadir)
 */
case class Overpass (date: DateTime, state: SpacecraftState, elev: Angle, scan: Angle, groundPos: GeoPosition, satPos: GeoPosition) extends Dated {
  // groundPos to satPos
  val azimuth: Angle = GreatCircle.finalBearing(groundPos,satPos)
  val dist2D: Length = GreatCircle.distance2D(groundPos, satPos)

  override def toString: String = {
    f"$date: elev=${elev.toDegrees}%.0f°, scan=${scan.toDegrees}%.1f°, azimuth=${azimuth.toDegrees}%.0f°, x-track=${dist2D.toKilometers}km"
  }
}

object OverpassSeq {
  val TIME = asc("time")
  val LAT = asc("lat")
  val LON = asc("lon")
  val ALT = asc("alt")
  val X = asc("x")
  val Y = asc("y")
  val Z = asc("z")

  /**
   * compute swath width for given height above ground and max scan angle of instrument, approximating earth as a sphere
   */
  def computeApproximateSwathWidth (alt: Length, maxScanAngle: Angle): Length = {
    val r = MeanEarthRadius.toMeters
    val d = r + alt.toMeters

    val c0 = Sin(maxScanAngle) / r
    val c1 = squared(r) - squared(d)
    val c2 = d * Cos(maxScanAngle)

    val a = c2 - squareRoot( squared(c2) + c1 )
    val alpha = Math.asin(c0 * a)

    Meters( r * alpha)
  }
}
import OverpassSeq._

/**
 * a time-ordered sequence of overpasses of the same satellite over a (sub-)set of ground positions
 * we keep the ground positions in here so that we can check which ones are covered by a single overpass
 */
case class OverpassSeq (satId: Int, overpasses: Array[Overpass], groundPositions: Array[GeoPosition]) extends JsonSerializable {

  var trajectory: Option[OrbitalTrajectory] = None
  var swathWidth: Option[Length] = None

  //--- computed values
  val firstDate = overpasses.head.date.toPrecedingMinute
  val lastDate = overpasses.last.date.toFollowingMinute
  val duration: Time = lastDate.timeSince(firstDate)
  val coverage: Double = overpasses.length.toDouble / groundPositions.length

  def date: DateTime = lastDate // this is the official time we use
  def iterator: Iterator[Overpass] = overpasses.iterator
  def foreach (f: Overpass=>Unit): Unit = overpasses.foreach(f)

  def includes (date: DateTime): Boolean = date.isWithin(firstDate, lastDate)

  def approximateSwathWidth (maxScanAngle: Angle): Option[Length] = {
    trajectory.map( trj => computeApproximateSwathWidth( trj.getAverageAltitude, maxScanAngle))
  }

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer
      .writeIntMember("satId", satId)
      .writeDateTimeMember("firstDate", firstDate)
      .writeDateTimeMember("lastDate", lastDate)
      .writeDoubleMember("coverage", coverage)

    ifSome(trajectory) { trj=> writer.writeArrayMember("trajectory")(serializeTrajectoryTo) }
    ifSome(swathWidth) { sw=> writer.writeDoubleMember("swath", sw.toMeters) }
  }

  def serializeTrajectoryTo (writer: JsonWriter): Unit = {
    trajectory.foreach { trj=>
      trj.foreach { (date, p) =>
        writer.writeObject { w=>
          w.writeDateTimeMember(TIME, date)
          w.writeDoubleMember( X, p.x)
          w.writeDoubleMember( Y, p.y)
          w.writeDoubleMember( Z, p.z)
        }
      }
    }
  }

  override def toString: String ={
    s"Overpass($satId,${firstDate.format_yMd_Hms_z}-${lastDate.format_Hms_z}, ${trajectory.map(_.size).getOrElse(0)}tps, ${swathWidth.map(_.toKilometers.round).getOrElse(0)}km)"
  }
}
