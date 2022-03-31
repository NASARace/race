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
package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceTick
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor}
import gov.nasa.race.ifTrue
import gov.nasa.race.track.{TrackDropped, Tracked3dObject, TrackedObject}

import scala.collection.Map
import scala.concurrent.duration._

case object CheckStaleTracks

/**
  * mix-in to generate/publish TrackDropped messages for stale track objects
  *
  * TODO - check if we can move the flights map into the concrete class so that it
  * can add its own storage without adding memory or performance penalties
  */
trait TrackDropper extends PublishingRaceActor with ContinuousTimeRaceActor with PeriodicRaceActor {

  //--- to be provided by concrete class
  val config: Config // actor config to be provided by concrete actor class
  val tracks: Map[String,TrackedObject] // map with last active FlightPos objects
  def removeStaleTrack(ac: TrackedObject): Unit // update map in implementor

  val publishDropped = config.getBooleanOrElse("publish-dropped", true)
  val dropAfterMillis = config.getFiniteDurationOrElse("drop-after", 60.seconds).toMillis // this is sim time
  override def defaultTickInterval = 30.seconds  // wall clock time

  override def onRaceTick(): Unit = removeStaleTracks

  /** likely to be overridden/replaced */
  def handleFPosDropperMessage: Receive = {
    case CheckStaleTracks => removeStaleTracks  // on demand
  }

  def removeStaleTracks = {
    val now = updatedSimTime
    val cut = dropAfterMillis

    tracks foreach { e =>    // make sure we don't allocate per entry
      val cs = e._1
      val ac = e._2
      val dt = elapsedSimTimeMillisSince(ac.date)
      if (dt > cut){
        removeStaleTrack(ac)  // provided by concrete class
        if (publishDropped) publish(TrackDropped(ac.id, ac.cs, now))  // FIXME ac.source ?
        info(s"dropping $cs after $dt msec")
      }
    }
  }
}
