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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.air.FlightPos
import gov.nasa.race.core.Messages.RaceTick
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.geo.GreatCircle._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._

import scala.language.postfixOps

class SimpleAircraft (val config: Config) extends ContinuousTimeRaceActor
             with SubscribingRaceActor with PublishingRaceActor with PeriodicRaceActor {

  //--- initialization from configuration
  val id = config.getString("id")
  val cs = config.getString("cs")

  // Ok to use vars here since nobody outside this actor will have access
  var pos = LatLonPos(Degrees(config.getDouble("lat")), Degrees(config.getDouble("lon")))
  var speed = Knots(config.getDouble("speed-kn"))
  var altitude = Feet(config.getDouble("altitude-ft"))
  var heading = Degrees(config.getDouble("heading"))

  //--- overridden initialization/termination callbacks

  override def onStartRaceActor(originator: ActorRef) = {
    startScheduler
    super.onStartRaceActor(originator)
  }

  //---  user message handler
  override def handleMessage = {
    case RaceTick =>
      updatePos
      debug(s"publishing $pos")
      publish(FlightPos(id, cs, pos, altitude, speed, heading, simTime))
  }

  //--- internal functions

  def updatePos: Unit = {
    val dist: Length = speed * updateElapsedSimTime
    pos = endPos(pos, dist, heading, altitude)
  }
}
