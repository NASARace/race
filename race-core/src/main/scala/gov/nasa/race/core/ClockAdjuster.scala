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

import gov.nasa.race.uom.DateTime
import gov.nasa.race.config.ConfigUtils._


/**
  * a trait that processes events that have a stored timestamp, conditionally
  * adjusting the simClock if the timestamp of the first checked events differs
  * for more than a configured duration.
  *
  * This is useful for replay if we don't a priori know the sim start time
  *
  * Note this only adjusts the clock if 'can-reset-clock' is set for this actor
  */
trait ClockAdjuster extends ContinuousTimeRaceActor {

  // clock adjustment has to be explicitly enabled in the config
  val canResetClock = config.getBooleanOrElse("can-reset-clock", false)

  // do we allow to set the clock forward (this is otherwise probably just bad data)
  val allowFutureReset = config.getBooleanOrElse("allow-future-reset", false)

  val maxSimClockDiff: Long = config.getOptionalFiniteDuration("max-clock-diff") match {  // optional, in sim time
    case Some(dur) => dur.toMillis
    case None => -1
  }

  private var isFirstClockCheck = true

  /**
    * this is the checker function that has to be called by the type that mixes in ClockAdjuster,
    * on each event that might potentially be the first one to trigger adjustment of the global clock
    */
  @inline final def checkInitialClockReset(d: DateTime) = {
    if (isFirstClockCheck) {
      if (canResetClock) checkClockReset(d)
      isFirstClockCheck = false
    }
  }

  // overridable in subtypes
  protected def checkClockReset(d: DateTime): Unit = {
    onSyncWithRaceClock // make sure there are no pending resets we haven't processed yet
    val elapsedMillis = elapsedSimTimeMillisSince(d)
    val dt = if (allowFutureReset) Math.abs(elapsedMillis) else elapsedMillis
    if (dt > maxSimClockDiff) {
      requestSimClockReset(d,simClock.timeScale)
    }
  }

  final def requestSimClockReset(d: DateTime, timeScale: Double = 1.0): Boolean = {
    if (canResetClock) {
      if (raceActorSystem.requestSimClockReset(self,d,timeScale)){
        onSyncWithRaceClock
        true
      } else {
        warning("RAS rejected sim clock reset")
        false
      }
    } else {
      warning("ignored clock reset request (set 'can-reset-clock')")
      false
    }
  }
}
