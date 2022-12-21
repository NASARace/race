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
import gov.nasa.race.ifSome

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * a RaceActor that receives periodic RaceTick messages, which should be processed by overridden onRaceTick() callbacks.
  *
  * note - if onStartRaceActor is not overridden it will start the scheduler according to the tick-interval setting
  */
trait PeriodicRaceActor extends RaceActor {
  sealed abstract class SchedulePolicy
  case object FixedDelay extends SchedulePolicy
  case object FixedRate extends SchedulePolicy

  // override these if derived type uses different keys
  val TickIntervalKey = "tick-interval"
  val TickDelayKey = "tick-delay"

  // override if there are different default values
  // NOTE - if overrides are computed in derived type ctors those values will not be visible here
  def defaultTickInterval: FiniteDuration = 5.seconds
  def defaultTickDelay: FiniteDuration = 0.seconds
  def defaultPolicy = FixedDelay

  // those cannot be lazy vals or defs because they might get updated from external config
  var tickInterval = Duration.Zero
  var tickDelay = Duration.Zero

  var schedule: Option[Cancellable] = None

  def handleRaceTick: Receive = {
    case RaceTick => onRaceTick()
  }

  override def handleSystemMessage: Receive = handleRaceTick orElse super.handleSystemMessage

  def onRaceTick(): Unit = {
    info("received RaceTick at")
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    tickInterval = config.getFiniteDurationOrElse(TickIntervalKey, defaultTickInterval)
    tickDelay = config.getFiniteDurationOrElse(TickDelayKey, defaultTickDelay)

    if (!isLocalContext(rc)) {
      // apply different remote config settings (if any)
      tickInterval = actorConf.getFiniteDurationOrElse(TickIntervalKey, tickInterval)
      tickDelay = actorConf.getFiniteDurationOrElse(TickDelayKey, tickDelay)
    }

    // note that scheduler is started before onStartRaceActor
    super.onInitializeRaceActor(rc, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    startScheduler
    super.onStartRaceActor(originator)
  }

  override def onPauseRaceActor(originator: ActorRef): Boolean = {
    stopScheduler
    super.onPauseRaceActor(originator)
  }

  override def onResumeRaceActor(originator: ActorRef): Boolean = {
    startScheduler
    super.onResumeRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    stopScheduler
    super.onTerminateRaceActor(originator)
  }

  // override if starting the scheduler should only happen under certain conditions
  def isReadyToSchedule: Boolean = true

  def startScheduler = {
    if (schedule.isEmpty && tickInterval.toMillis > 0 && isReadyToSchedule) {
      schedule = Some(scheduler.scheduleWithFixedDelay(tickDelay, tickInterval, self, RaceTick))
    }
  }

  def stopScheduler = {
    ifSome(schedule) { sched =>
      sched.cancel()
      schedule = None
    }
  }

  def isSchedulerStarted: Boolean = schedule.isDefined

  override def commitSuicide (errMsg: String) = {
    stopScheduler
    super.commitSuicide(errMsg)
  }
}
