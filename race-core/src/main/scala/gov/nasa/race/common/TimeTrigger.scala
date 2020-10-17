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

import gov.nasa.race.uom.Time.{HMS, Milliseconds}
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.DateTimeUtils

/**
  * a factory for TimeTriggers
  */
object TimeTrigger {

  def apply (spec: CharSequence): TimeTrigger = {
    spec match {
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
  *  TimeTriggers need to be armed before first use
  */
trait TimeTrigger {

  protected var fired = true

  protected def checkFired (date: DateTime): Boolean

  def check (date: DateTime): Boolean = {
    if (!fired) {
      fired = checkFired(date)
      fired
    } else {
      false
    }
  }

  def armCheck (refDate: DateTime): Unit = {
    fired = false
  }

  def toSpecString: String
  override def toString: String = s"${getClass.getSimpleName}($toSpecString)"
}

class TimeOfDayTrigger (val timeOfDay: Time) extends TimeTrigger {
  def checkFired (date: DateTime): Boolean = date.getTimeOfDay > timeOfDay

  def toSpecString: String = timeOfDay.showHHMMSS
}

class SinceTrigger (val duration: Time) extends TimeTrigger {
  var lastRefDate: DateTime = DateTime.UndefinedDateTime

  def checkFired (date: DateTime): Boolean = date - lastRefDate > duration

  override def armCheck (refDate: DateTime): Unit = {
    fired = false
    lastRefDate = refDate
  }

  def toSpecString: String = duration.showMillis
}

/**
  * a singleton TimeTrigger that never fires
  */
object NoTrigger extends TimeTrigger {
  def checkFired (date: DateTime): Boolean = false
  override def armCheck (refDate: DateTime): Unit = {}
  def toSpecString: String = ""
}

// .. TODO add DayTrigger, DayOfMonthTrigger and more