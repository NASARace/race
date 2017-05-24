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

import gov.nasa.race.air.TATrack.Status.Status
import gov.nasa.race.geo.{LatLonPos, XYPos}
import gov.nasa.race.uom.{Angle, Length, Speed}
import org.joda.time.DateTime

object TATrack {
  object Status extends Enumeration {
    type Status = Value
    val Active,Coasting,Drop,Undefined = Value
  }

  // we don't use a case class so that we can have a class hierarchy, but we still want to be able to pattern match
  def apply (src: String, trackNum: Int, xyPos: XYPos, vVert: Speed, status: Status,
             isFrozen: Boolean, isNew: Boolean, isPseudo: Boolean, isAdsb: Boolean, beaconCode: String,
             stddsRev: Int, hasFlightPlan: Boolean,
             flightId: String, cs: String, position: LatLonPos, altitude: Length, speed: Speed, heading: Angle, date: DateTime) = {
    new TATrack(src,trackNum,xyPos,vVert,status,isFrozen,isNew,isPseudo,isAdsb,beaconCode,
                stddsRev,hasFlightPlan,
                flightId,cs,position,altitude,speed,heading,date)
  }
  def unapply (src: String, trackNum: Int, xyPos: XYPos, vVert: Speed, status: Status,
               isFrozen: Boolean, isNew: Boolean, isPseudo: Boolean, isAdsb: Boolean, beaconCode: String,
               stddsRev: Int, hasFlightPlan: Boolean,
               flightId: String, cs: String, position: LatLonPos, altitude: Length, speed: Speed, heading: Angle, date: DateTime) = {
    (src,trackNum,xyPos,vVert,status,isFrozen,isNew,isPseudo,isAdsb,beaconCode,stddsRev,hasFlightPlan,flightId,cs,position,altitude,speed,heading,date)
  }
  def unapply (track: TATrack) = true
}

/**
  * a specialized FlightPos that represents tracks from TAIS/STARS messages
  *
  * we could roll the flags into a bit set, but keeping them as separate fields is better for pattern matching
  */
class TATrack (val src: String,
               val trackNum: Int,
               val xyPos: XYPos,
               val vVert: Speed,
               val status: Status,
               val isFrozen: Boolean,
               val isNew: Boolean,
               val isPseudo: Boolean,
               val isAdsb: Boolean,
               val beaconCode: String,

               val stddsRev: Int,  // 2 or 3  TODO - not sure we want to keep this in a track object
               val hasFlightPlan: Boolean,  // TODO - should be replaced by FlightPlan reference

               //--- the FlightPos fields
               flightId: String, cs: String, position: LatLonPos, altitude: Length, speed: Speed, heading: Angle, date: DateTime
              ) extends FlightPos(flightId,cs,position,altitude,speed,heading,date) {

  override def toString = {
    f"TATrack($src,$trackNum,$xyPos,$status, $position,$altitude,$heading,$speed, $date)"
  }

  def isDrop = status == TATrack.Status.Drop
}