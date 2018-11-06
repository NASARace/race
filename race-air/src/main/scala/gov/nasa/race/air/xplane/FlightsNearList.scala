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

package gov.nasa.race.air.xplane

import gov.nasa.race.air.TrackedAircraft
import gov.nasa.race.common.WeightedArray
import gov.nasa.race.geo.{GeoPosition, ProximityList, _}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._
import gov.nasa.race.util.StringUtils


/**
  * proximity list entry - this represents a FlightPos we need to map into XpAircraft
  */
class FlightNearEntry(var obj: TrackedAircraft,
                      var weight: Double,
                      var xpIndex: Int
                ) extends WeightedArray.Entry[TrackedAircraft]

/**
  *  dynamic proximity list of FlightPos objects that have to be mapped to XpAircraft entries
  *  This is the bus-facing data structure that is used to update the respective entry positions in the XpAircraft list
  */
class FlightsNearList (center: GeoPosition, maxDistance: Length, aircraftList: ExternalAircraftList)
           extends ProximityList[TrackedAircraft,FlightNearEntry](center,maxDistance, NauticalMiles(10)) {
  override protected val array = new Array[FlightNearEntry](aircraftList.length)

  override def createEntry(obj: TrackedAircraft, weight: Double): FlightNearEntry = {
    new FlightNearEntry(obj,weight,aircraftList.assign(obj))
  }

  override def setEntry (e: FlightNearEntry, obj: TrackedAircraft, weight: Double) = {
    super.setEntry(e,obj,weight)
    aircraftList.set(e.xpIndex, obj)
  }

  override def releaseEntry(e: FlightNearEntry): Unit = aircraftList.release(e.xpIndex)

  override def isSame (a: TrackedAircraft, b: TrackedAircraft): Boolean = a.cs == b.cs

  override def toString: String = StringUtils.mkString[FlightNearEntry](this, "FlightsNear [", ",", "]") { e =>
    f"${e.obj.cs}:${e.weight/NM.toMeters}%.2f"
  }
}
