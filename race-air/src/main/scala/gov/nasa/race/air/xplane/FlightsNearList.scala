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

import gov.nasa.race.air.FlightPos
import gov.nasa.race.geo._
import gov.nasa.race.common.WeightedArray
import gov.nasa.race.geo.{LatLonPos, ProximityList}
import gov.nasa.race.util.StringUtils
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._


/**
  * proximity list entry - this represents a FlightPos we need to map into XpAircraft
  */
class FPosEntry (var obj: FlightPos, var weight: Double, var xpIndex: Int) extends WeightedArray.Entry[FlightPos]

/**
  *  dynamic proximity list of FlightPos objects that have to be mapped to XpAircraft entries
  *  This is the bus-facing data structure that is used to update the respective entry positions in the XpAircraft list
  */
class FlightsNearList (center: LatLonPos, maxDistance: Length, xpAircraft: XPlaneAircraftList)
           extends ProximityList[FlightPos,FPosEntry](center,maxDistance, NauticalMiles(10)) {
  override protected val array = new Array[FPosEntry](xpAircraft.length)

  override def createEntry(obj: FlightPos, weight: Double): FPosEntry = {
    new FPosEntry(obj,weight,xpAircraft.assign(obj))
  }

  override def setEntry (e: FPosEntry, obj: FlightPos, weight: Double) = {
    super.setEntry(e,obj,weight)
    xpAircraft.set(e.xpIndex, obj)
  }

  override def releaseEntry(e: FPosEntry) = xpAircraft.release(e.xpIndex)

  override def isSame (a: FlightPos, b: FlightPos) = a.cs == b.cs

  override def toString: String = StringUtils.mkString[FPosEntry](this, "FlightsNear [", ",", "]") { e =>
    f"${e.obj.cs}:${e.weight/NM}%.2f"
  }
}
