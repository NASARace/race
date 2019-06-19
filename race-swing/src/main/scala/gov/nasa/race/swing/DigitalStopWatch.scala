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

import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.common.{Clock, SettableClock}
import gov.nasa.race.swing.Style._

import scala.concurrent.duration._
import scala.swing.{GridPanel, Label}

/**
 * a stopwatch, i.e. a clock that can be paused/resumed/stopped
 */
class DigitalStopWatch (val clock: Clock)  extends GridPanel(1,1) {

  class StopWatchLabel(txt: String = null) extends Label(txt) {
    def update = {
      val (h, m, s) = toHHMMSS(clock.elapsed)
      text = f" $h%02d:$m%02d:$s%02d "
    }
  }

  val timer = new SwingTimer(1.second)
  val clockTime = new StopWatchLabel(" 00:00:00 ").styled("time")

  contents += clockTime

  timer.bindTo(this)
  timer.whenExpired {
    if (!showing) timer.stop
    else clockTime.update
  }
}

class SettableDigitalStopWatch (settableClock: SettableClock) extends DigitalStopWatch(settableClock) {

  def pause = stop

  def resume = {
    settableClock.resume
    timer.restart
  }
  def stop = {
    settableClock.stop
    timer.stop
  }

}
