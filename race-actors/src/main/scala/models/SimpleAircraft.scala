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

package gov.nasa.race.actors.models

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.data._
import gov.nasa.race.data.GreatCircle._
import gov.nasa.race.core._
import squants.motion._
import squants.space._


import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class SimpleAircraft (val config: Config) extends ContinuousTimeRaceActor with SubscribingRaceActor with PublishingRaceActor {

  case object UpdateFlightPos // local message to update & publish flight pos

  //--- flight data (we keep this separate from gov.nasa.race.data.FlightPos so that we can extend the model independently from output format)
  val id = config.getString("id")
  val cs = config.getString("cs")

  var pos = LatLonPos(Degrees(config.getDouble("lat")), Degrees(config.getDouble("lon")))
  var speed = Knots(config.getDouble("speed-kn"))
  var altitude = Feet(config.getDouble("altitude-ft"))
  var heading = Degrees(config.getDouble("heading"))

  var publishInterval = config.getFiniteDurationOrElse("interval", 1 second)

  var schedule: Option[Cancellable] = None

  // user message handler - this is not called before we are initialized
  override def handleMessage = {
    case UpdateFlightPos =>
      updatePos
      debug(s"publishing $pos")
      publish(FlightPos(id, cs, pos, altitude, speed, heading, simTime))
  }

  //--- overridden system hooks

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    schedule = Some(scheduler.schedule(0 seconds, publishInterval, self, UpdateFlightPos))
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    ifSome(schedule){ _.cancel }
  }

  //--- internal functions

  def updatePos: Unit = {
    val dist: Length = speed * updateElapsedSimTime
    pos = endPos(pos, dist, heading, altitude)
  }
}
