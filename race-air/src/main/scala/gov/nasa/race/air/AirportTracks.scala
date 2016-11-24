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

import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.util.DateTimeUtils._
import org.joda.time.DateTime
import gov.nasa.race.uom._

/**
  * track report for airports (e.g. generated from SWIM asdexMsg messages)
  */
class AirportTracks (val airport: String, val tracks: Seq[Track]) {
  override def toString = {
    val d = if (!tracks.isEmpty) hhmmssZ.print(tracks.head.date) else "?"
    s"AirportTracks{$airport,date=$d,nTracks=${tracks.size}}"
  }
}


object TrackType extends Enumeration {
  type TrackType = Value
  val AIRCRAFT, VEHICLE, UNKNOWN = Value
}

case class Track (trackType: TrackType.Value,
                  id: String,
                  date: DateTime,
                  pos: LatLonPos,
                  speed: Option[Speed],
                  heading: Option[Angle],
                  drop: Boolean,
                  // optional fields for aircraft
                  acId: Option[String],
                  acType: Option[String],
                  altitude: Option[Length]) {

  def isAircraft = trackType == TrackType.AIRCRAFT
  def isGroundAircraft = trackType == TrackType.AIRCRAFT && !altitude.isDefined
  def isMovingGroundAircraft = isGroundAircraft && heading.isDefined
  def isAirborneAircraft = trackType == TrackType.AIRCRAFT && altitude.isDefined
  def isVehicle = trackType == TrackType.VEHICLE

  override def toString = s"Track{$id,$trackType,$pos,$date}"
}
