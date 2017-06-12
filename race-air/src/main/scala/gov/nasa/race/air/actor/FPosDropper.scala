/*
 * Copyright (c) 2017, United States Government, as represented by the
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
import gov.nasa.race.air.{FlightDropped, FlightPos}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.RaceTick
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor}
import gov.nasa.race.ifTrue

import scala.collection.Map
import scala.concurrent.duration._

case object CheckStaleFlightPos

/**
  * mix-in to generate/publish FlightDropped messages for stale FlightPos objects
  *
  * TODO - check if we can move the flights map into the concrete class so that it
  * can add its own storage without adding memory or performance penalties
  */
trait FPosDropper extends PublishingRaceActor with ContinuousTimeRaceActor with PeriodicRaceActor {

  //--- to be provided by concrete class
  val config: Config // actor config to be provided by concrete actor class
  val flights: Map[String,FlightPos] // map with last active FlightPos objects
  def removeStaleFlight (fpos: FlightPos) // update map in implementor

  val publishDropped = config.getBooleanOrElse("publish-dropped", true)
  val dropAfterMillis = config.getFiniteDurationOrElse("drop-after", 60.seconds).toMillis // this is sim time
  override def defaultTickInterval = 30.seconds  // wall clock time

  override def onStartRaceActor(originator: ActorRef) = {
    ifTrue (super.onStartRaceActor(originator)){
      startScheduler
    }
  }

  /** likely to be overridden/replaced */
  def handleFPosDropperMessage: Receive = {
    case RaceTick => removeStaleFlights
    case CheckStaleFlightPos => removeStaleFlights  // on demand
  }

  def removeStaleFlights = {
    val now = updatedSimTime
    val cut = dropAfterMillis

    flights foreach { e =>    // make sure we don't allocate per entry
      val cs = e._1
      val fpos = e._2
      val dt = elapsedSimTimeMillisSince(fpos.date)
      if (dt > cut){
        removeStaleFlight(fpos)  // provided by concrete class
        if (publishDropped) publish(FlightDropped(fpos.flightId, fpos.cs, now))
        info(s"dropping $cs after $dt msec")
      }
    }
  }
}
