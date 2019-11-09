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
package gov.nasa.race.core

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.RaceTick
import gov.nasa.race.ifSome

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * a RaceActor that receives periodic RaceCheck messages, which should be processed
  * by its handleMessage().
  *
  * NOTE - it is up to the concrete actor to decide when to call startScheduler()
  * (from ctor, initializeRaceActor or startRaceActor)
  * This honors remote config tick-interval specs, which would re-start an already
  * running scheduler
  */
trait PeriodicRaceActor extends RaceActor {
  final val TickIntervalKey = "tick-interval"
  final val TickDelayKey = "tick-delay"

  // override if there are different default values
  def defaultTickInterval = 5.seconds
  def defaultTickDelay = 0.seconds
  def tickMessage = RaceTick

  var tickInterval = config.getFiniteDurationOrElse(TickIntervalKey, defaultTickInterval)
  var tickDelay = config.getFiniteDurationOrElse(TickDelayKey, defaultTickDelay)
  var schedule: Option[Cancellable] = None

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    if (!isLocalContext(rc)) {
      // check if we have a different remote tick interval
      if (actorConf.hasPath(TickIntervalKey)) {
        tickInterval = actorConf.getFiniteDuration(TickIntervalKey)
        if (schedule.isDefined){
          stopScheduler
          startScheduler
        }
      }
    }

    super.onInitializeRaceActor(rc, actorConf)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    stopScheduler
    super.onTerminateRaceActor(originator)
  }

  def startScheduler = {
    if (schedule.isEmpty) {
      schedule = Some(scheduler.scheduleWithFixedDelay(tickDelay, tickInterval, self, tickMessage))
    }
  }

  def stopScheduler = {
    ifSome(schedule) { sched =>
      sched.cancel()
      schedule = None
    }
  }

  override def commitSuicide (errMsg: String) = {
    stopScheduler
    super.commitSuicide(errMsg)
  }
}
