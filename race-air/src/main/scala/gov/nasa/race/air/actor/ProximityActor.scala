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

package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.air.{FlightCompleted, FlightCsChanged, FlightDropped, FlightPos}
import gov.nasa.race.common.WeightedArray
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, SubscribingRaceActor}
import gov.nasa.race.geo.{LatLonPos, ProximityList}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._

object ProximityActor {
  class FlightPosEntry(var obj: FlightPos, var weight: Double)
                                         extends WeightedArray.Entry[FlightPos] {
    override def toString = f"(${weight.toInt}:'${obj.cs}')" // weight is in [meter]
  }
}

/**
  * actor that reports proximity contacts as an ordered list
  * used for testing and debugging purposes
  *
  * TODO turn this into a filter actor that publishes all flights that are in the list, and drops what gets kicked out
  */
class ProximityActor (val config: Config) extends SubscribingRaceActor {
  import ProximityActor._

  val center =  LatLonPos(Degrees(config.getDouble("lat")), Degrees(config.getDouble("lon")))
  val maxDist = NauticalMiles(config.getDoubleOrElse("dist", 20))
  val maxEntries = config.getIntOrElse("max-entries", 5)

  val proximities = new ProximityList[FlightPos,FlightPosEntry](center,maxDist, maxDist*2) {
    override protected val array: Array[FlightPosEntry] = new Array[FlightPosEntry](maxEntries)
    override def createEntry(obj: FlightPos, dist: Double): FlightPosEntry = new FlightPosEntry(obj,dist)
    override def isSame (a: FlightPos, b: FlightPos) = a.cs == b.cs
    override def releaseEntry(e: FlightPosEntry) = info(s"    removed '${e.obj.cs}'")
    override def toString = mkString("[", ",", "]")
  }

  override def handleMessage = {
    case BusEvent(_,csChange:FlightCsChanged,_) => changeCs(csChange.oldCS, csChange.cs)
    case BusEvent(_,fpos:FlightPos,_) => update(fpos)
    case BusEvent(_,fdrop:FlightDropped,_) => drop(fdrop.cs)
    case BusEvent(_,fcomplete:FlightCompleted,_) => drop(fcomplete.cs)
  }

  def changeCs (oldCS: String, newCS: String) = {
    if (proximities.updateObjects(e => if (e.obj.cs == oldCS) e.obj.copy(cs=newCS) else e.obj)){
      info(s"change: $proximities")
    }
  }

  def update (fpos: FlightPos): Unit = {
    if (proximities.updateWith(fpos) >= 0) {
      info(s"update: $proximities")
    }
  }

  def drop (cs: String) = {
    if (proximities.removeEvery(e=> e.obj.cs == cs)) {
      info(s"remove: $proximities")
    }
  }
}
