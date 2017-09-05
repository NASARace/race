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
}

/**
  * a specialized TrackedAircraft that represents tracks from TAIS/STARS messages
  */
case class TATrack (val src: String,

                    val id: String,
                    val cs: String,
                    val position: LatLonPos,
                    val altitude: Length,
                    val speed: Speed,
                    val heading: Angle,
                    val date: DateTime,

                    val xyPos: XYPos,
                    val vVert: Speed,
                    val status: Status,
                    val attrs: Int,
                    val beaconCode: String,
                    val flightPlan: Option[FlightPlan]
                  ) extends TrackedAircraft {

  override def toString = {
    f"TATrack($src,$id,$xyPos,$status, $position,$altitude,$heading,$speed, $date, $flightPlan)"
  }

  def isDrop = status == TATrack.Status.Drop
  def isFrozen = (attrs & TATrack.FrozenFlag) > 0
  def isNew = (attrs & TATrack.NewFlag) > 0
  def isPseudo = (attrs & TATrack.PseudoFlag) > 0
  def isAdsb = (attrs & TATrack.AdsbFlag) > 0

  def hasFlightPlan = flightPlan.isDefined
}