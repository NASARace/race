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
  // various bit flags for TATrack attrs
  final val FrozenFlag = 1
  final val NewFlag = 2
  final val PseudoFlag = 4
  final val AdsbFlag = 8

  object Status extends Enumeration {
    type Status = Value
    val Active,Coasting,Drop,Undefined = Value
  }

  // we don't use a case class so that we can have a class hierarchy, but we still want to be able to pattern match
  def apply (src: String, trackNum: Int, xyPos: XYPos, vVert: Speed, status: Status,
             attrs: Int, beaconCode: String, flightPlan: Option[FlightPlan],
             flightId: String, cs: String, position: LatLonPos, altitude: Length, speed: Speed, heading: Angle, date: DateTime) = {
    new TATrack(src,trackNum,xyPos,vVert,status,attrs,beaconCode,flightPlan,
                flightId,cs,position,altitude,speed,heading,date)
  }
  def unapply (src: String, trackNum: Int, xyPos: XYPos, vVert: Speed, status: Status,
               attrs: Int, beaconCode: String, flightPlan: Option[FlightPlan],
               flightId: String, cs: String, position: LatLonPos, altitude: Length, speed: Speed, heading: Angle, date: DateTime) = {
    (src,trackNum,xyPos,vVert,status,attrs,beaconCode,flightPlan,flightId,cs,position,altitude,speed,heading,date)
  }
  def unapply (track: TATrack) = true
}

/**
  * a specialized FlightPos that represents tracks from TAIS/STARS messages
  */
class TATrack (val src: String,
               val trackNum: Int,
               val xyPos: XYPos,
               val vVert: Speed,
               val status: Status,
               val attrs: Int,
               val beaconCode: String,
               val flightPlan: Option[FlightPlan],

               //--- the FlightPos fields
               flightId: String, cs: String, position: LatLonPos, altitude: Length, speed: Speed, heading: Angle, date: DateTime
              ) extends FlightPos(flightId,cs,position,altitude,speed,heading,date) {

  override def toString = {
    f"TATrack($src,$trackNum,$xyPos,$status, $position,$altitude,$heading,$speed, $date, $flightPlan)"
  }

  override def equals (other: Any): Boolean = {
    super.equals(other) && (other match {
      case o:TATrack =>
        src == o.src &&
        trackNum == o.trackNum &&
        xyPos == o.xyPos &&
        vVert == o.vVert &&
        status == o.status &&
        attrs == o.attrs &&
        beaconCode == o.beaconCode
      case somethingElse => false
    })
  }

  def isDrop = status == TATrack.Status.Drop
  def isFrozen = (attrs & TATrack.FrozenFlag) > 0
  def isNew = (attrs & TATrack.NewFlag) > 0
  def isPseudo = (attrs & TATrack.PseudoFlag) > 0
  def isAdsb = (attrs & TATrack.AdsbFlag) > 0

  def hasFlightPlan = flightPlan.isDefined
}