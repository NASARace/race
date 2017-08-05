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
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom._
import gov.nasa.race.util.DateTimeUtils._
import org.joda.time.DateTime

/**
  * track report for airports (e.g. generated from SWIM asdexMsg messages)
  */
class AsdexTracks(val airport: String, val tracks: Seq[AsdexTrack]) {
  override def toString = {
    val d = if (!tracks.isEmpty) hhmmssZ.print(tracks.head.date) else "?"
    s"AirportTracks{$airport,date=$d,nTracks=${tracks.size}}"
  }
}


object AsdexTrackType extends Enumeration {
  type AsdexTrackType = Value
  val Aircraft, Vehicle, Unknown = Value
}

object VerticalDirection extends Enumeration {
  type VerticalDirection = Value
  val Up, Down, Unknown = Value
}

case class AsdexTrack(id: String,
                      cs: String,
                      date: DateTime,
                      position: LatLonPos,

                      // note - these can have undefined values (we don't use Option to be abe to merge with FlightPos)
                      speed: Speed,
                      heading: Angle,
                      altitude: Length,

                      // asde-x specifics
                      trackType: AsdexTrackType.Value,
                      display: Boolean,
                      drop: Boolean,
                      vertical: VerticalDirection.Value, // up/down
                      onGround: Boolean, // ground bit set
                      acType: Option[String]
                     ) extends TrackedObject {

  def isAircraft = trackType == AsdexTrackType.Aircraft
  def isGroundAircraft = trackType == AsdexTrackType.Aircraft && !altitude.isDefined
  def isAirborne = altitude.isDefined
  def isMovingGroundAircraft = isGroundAircraft && heading.isDefined
  def isAirborneAircraft = trackType == AsdexTrackType.Aircraft && altitude.isDefined
  def isVehicle = trackType == AsdexTrackType.Vehicle

  override def toShortString = s"Track{$id,$trackType,$position,$date}"
}
