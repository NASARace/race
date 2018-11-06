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
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.uom.{Angle, Length, Speed}
import org.joda.time.DateTime

object TATrack {
  // status bit flags for TATrack attrs (should be >0xffff)

  final val PseudoFlag     = 0x10000
  final val AdsbFlag       = 0x20000
  final val CoastingFlag   = 0x40000

  object Status extends Enumeration {
    type Status = Value
    val Active,Coasting,Drop,Undefined = Value
  }
}

/**
  * a specialized TrackedAircraft that represents tracks from TAIS/STARS messages
  */
case class TATrack (id: String,
                    cs: String,
                    position: GeoPosition,
                    altitude: Length,
                    heading: Angle,
                    speed: Speed,
                    vr: Speed,
                    date: DateTime,
                    status: Int,

                    src: String,
                    xyPos: XYPos,
                    beaconCode: String,
                    flightPlan: Option[FlightPlan]
                  ) extends TrackedAircraft {
  import TATrack._

  override def toString = {
    f"TATrack($src,$id,$cs,0x${status.toHexString},$position,${altitude.toFeet}%.0f,${heading.toDegrees}%.0f°,${speed.toKnots}%.1fkn,${vr.toFeetPerMinute}%.0f, $date, $flightPlan)"
  }

  def isPseudo = (status & PseudoFlag) != 0
  def isAdsb = (status & AdsbFlag) != 0

  def hasFlightPlan = flightPlan.isDefined
}