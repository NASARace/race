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

package gov.nasa.race.swing

import Style._
import gov.nasa.race.common.Clock

import scala.concurrent.duration._
import gov.nasa.race.uom.{DateTime, Time}
import java.time.format.DateTimeFormatter

import scala.swing.{GridPanel, Label}

/**
 * Panel that contains a digital clock with date and time
 */
class DigitalClock (private[this] var clock: Option[Clock]=None) extends GridPanel(2,1) {

  protected var lastTimeOfDay: Time = Time.UndefinedTime

  abstract class ClockLabel (txt: String) extends Label(txt) {
    def update (d: DateTime): Unit
  }

  class DateLabel (txt: String=null) extends ClockLabel(txt) {
    def update (d: DateTime): Unit = {
      text = d.format_E_Mdy
    }
  }

  class TimeLabel (txt: String=null) extends ClockLabel(txt) {
    def update (d: DateTime): Unit = {
      text = d.format_Hms_z
    }
  }

  val clockDate = new DateLabel("... 00-00-0000").styled("date")
  val clockTime = new TimeLabel("00:00:00").styled("time")

  val timer = new SwingTimer(1.second)


  contents ++= Seq(clockDate,clockTime)

  timer.bindTo(this)
  timer.whenExpired {
    if (!showing) {
      timer.stop

    } else {
      val dt = getDateTime
      val td = dt.getTimeOfDay
      if (lastTimeOfDay.isUndefined || td < lastTimeOfDay) clockDate.update(dt) // only need update after wrap around
      lastTimeOfDay = td
      clockTime.update(dt)
    }
  }

  def setClock (newClock: Clock) = clock = Some(newClock)
  def getDateTime = clock match {
    case None => DateTime.now
    case Some(c) => c.dateTime
  }

  def pause = timer.stop
  def resume = timer.restart
  def stop = timer.stop
}

