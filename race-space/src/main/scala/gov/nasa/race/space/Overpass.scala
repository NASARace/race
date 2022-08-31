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

import gov.nasa.race.Dated
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
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

/**
 * a time-ordered sequence of overpasses of the same satellite over a (sub-)set of ground positions
 * we keep the ground positions in here so that we can check which ones are covered by a single overpass
 */
case class OverpassSeq (satId: Int, overpasses: Array[Overpass], groundPositions: Array[GeoPosition]) extends JsonSerializable {

  //--- computed values
  val firstDate = overpasses.head.date.toPrecedingMinute
  val lastDate = overpasses.last.date.toFollowingMinute
  val duration: Time = lastDate.timeSince(firstDate)
  val coverage: Double = overpasses.length.toDouble / groundPositions.length

  def date: DateTime = lastDate // this is the official time we use
  def iterator: Iterator[Overpass] = overpasses.iterator
  def foreach (f: Overpass=>Unit): Unit = overpasses.foreach(f)

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer.writeIntMember("satId", satId)
    writer.writeDateTimeMember("firstDate", firstDate)
    writer.writeDateTimeMember("lastDate", lastDate)
    writer.writeDoubleMember("coverage", coverage)
  }

  override def toString: String =s"Overpass(${firstDate.format_yMd_Hms_z}-${lastDate.format_Hms_z})"
}
