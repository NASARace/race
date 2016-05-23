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

import Reactors._
import Style._
import gov.nasa.race.common.Clock
import scala.concurrent.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scala.swing.{Label, GridPanel}

/**
 * Panel that contains a digital clock with date and time
 */
class DigitalClock (private[this] var clock: Option[Clock]=None) extends GridPanel(2,1) {

  class ClockLabel (fmtString: String, txt: String=null) extends Label(txt) {
    val fmt = DateTimeFormat.forPattern(fmtString)
    def update(d: DateTime) = text = fmt.print(d)
  }

  val timer = new SwingTimer(1.second)
  val clockDate = new ClockLabel(" EE, MM/dd/yyyy").styled('date)
  val clockTime = new ClockLabel(" hh:mm:ss a ", " 00:00:00 AM ").styled('time)

  contents ++= Seq(clockDate,clockTime)

  timer.bindTo(this)
  timer.whenExpired {
    if (!showing) {
      timer.stop
    } else {
      val dt = getDateTime
      clockDate.update(dt)
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

