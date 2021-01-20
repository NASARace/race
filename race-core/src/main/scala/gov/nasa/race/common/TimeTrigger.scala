/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.common

import gov.nasa.race.uom.Time.{HMS, Hours, Milliseconds}
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.DateTimeUtils

/**
  * a factory for TimeTriggers
  */
object TimeTrigger {

  // minimalistic parser
  def apply (spec: CharSequence): TimeTrigger = {
    spec match {
      case "H" => new HourlyTrigger()  // every hour
      case "m" => new MinutelyTrigger() // every minute
      case "s" => new SecondlyTrigger() // every second
      case "<never>" => NoTrigger  // never triggered
      case "<always>" => AlwaysTrigger // triggered on each check

      case DateTimeUtils.hhmmssRE(hh,mm,ss) => new TimeOfDayTrigger(HMS(hh.toInt, mm.toInt, ss.toInt))
      case _ => new SinceTrigger( Milliseconds(DateTimeUtils.durationMillis(spec).toInt))
    }
  }
}

/**
  * something that can exceed a duration (which can also be a time-of-day) and has a check state
  *
  *   ooooo00000000000000000000001oooooooooo0000000
  *   -----X--------------X------|----------X---------> t
  *     armCheck()       t_x   t_test    armCheck()
  *
  *  only the first check at t > t_x after a call to armCheck() fires
  *
  *  TimeTriggers need to be armed before first use. They re-arm themselves when they fire
  */
trait TimeTrigger {

  protected var armed = false

  protected def checkFiring(date: DateTime): Boolean

  def check (date: DateTime): Boolean = armed && checkFiring(date)

  def armCheck (startDate: DateTime): Unit

  def toSpecString: String
  override def toString: String = s"${getClass.getSimpleName}($toSpecString)"
}

/**
  * a time trigger that fires on the first check after the last fire that exceeds a given duration
  *
  * firing date is updated in duration increments, i.e. does not depend on the last check time that fired. This
  * compensates for missed intervals
  */
class SinceTrigger (val duration: Time) extends TimeTrigger {
  var nextFiringDate: DateTime = DateTime.UndefinedDateTime

  def checkFiring (date: DateTime): Boolean = {
    if (date >= nextFiringDate) {
      var d = nextFiringDate + duration
      while (d <= date) d = d + duration
      nextFiringDate = d
      true
    } else false
  }

  def armCheck (startDate: DateTime): Unit = {
    armed = true
    nextFiringDate = startDate
  }

  def toSpecString: String = duration.showMillis
}

/**
  * a time trigger that fires each 24h on the first check after the specified time of day
  */
class TimeOfDayTrigger(val timeOfDay: Time) extends SinceTrigger(Hours(24)) {
  override def toSpecString: String = timeOfDay.showHHMMSS

  override def armCheck (startDate: DateTime): Unit = {
    super.armCheck(startDate.atTimeOfDay(timeOfDay))
  }
}

class HourlyTrigger (nHours: Int = 1) extends SinceTrigger(Time.Hours(nHours)) {
  override def armCheck (startDate: DateTime): Unit = {
    super.armCheck(startDate.atLastHour)
  }
}

class MinutelyTrigger (nMinutes: Int = 1) extends SinceTrigger(Time.Minutes(nMinutes)) {
  override def armCheck (startDate: DateTime): Unit = {
    super.armCheck(startDate.atLastMinute)
  }
}

class SecondlyTrigger (nSeconds: Int = 1) extends SinceTrigger(Time.Seconds(nSeconds)) {
  override def armCheck (startDate: DateTime): Unit = {
    super.armCheck(startDate.atLastSecond)
  }
}

/**
  * a singleton TimeTrigger that never fires
  */
object NoTrigger extends TimeTrigger {
  def checkFiring(date: DateTime): Boolean = false
  override def armCheck (startDate: DateTime): Unit = {}
  def toSpecString: String = "<never>"
}

/**
  * a singleton TimeTrigger that always fires if it is armed
  */
object AlwaysTrigger extends TimeTrigger {
  def checkFiring(date: DateTime): Boolean = armed
  override def armCheck (startDate: DateTime): Unit = { armed = true }
  def toSpecString: String = "<always>"
}

// .. TODO add DayTrigger, DayOfMonthTrigger and more