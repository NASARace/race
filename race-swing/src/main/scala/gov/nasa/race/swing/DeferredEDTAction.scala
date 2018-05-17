/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import java.awt.event.{ActionEvent,ActionListener}
import javax.swing.Timer

object DeferredEDTAction {

  // syntactic suger
  def apply (minDelay: Int, maxDelay: Int)(action: => Unit) = {
    new DeferredEDTAction(minDelay,maxDelay,action)
  }
}

/**
  * a utility class to execute generic actions in the EDT, making sure deferred execution happens
  * within the time window [current - lastRun > minDelay, current - scheduleTime < maxDelay]
  *
  * @param minDelay minimum duration in msec between last run or scheduling and execution
  * @param maxDelay maximum duration in msec between scheduling and execution
  * @param action to perform once within time window
  *
  * Note that AWT makes sure this is only executed after all pending events have been processed
  */
class DeferredEDTAction (minDelay: Int, maxDelay: Int, action: => Unit) extends ActionListener {

  private var scheduleTime: Long = 0
  private var lastRunTime: Long = 0

  val timer = new Timer(minDelay,this)

  def schedule (initDelay: Int, interval: Int=minDelay): Unit = {
    if (scheduleTime == 0) {
      scheduleTime = System.currentTimeMillis
      lastRunTime = scheduleTime
      timer.setInitialDelay(initDelay)
      timer.setDelay(interval)
      timer.start
    }
  }

  def schedule: Unit = {
    if (scheduleTime == 0) {
      scheduleTime = System.currentTimeMillis
      lastRunTime = scheduleTime

      timer.setInitialDelay(minDelay)
      timer.setDelay(minDelay)
      timer.start
    }
  }

  /**
    * override if execution has to meet non-timing constraints
    */
  protected def isReady: Boolean = true

  override def actionPerformed (e: ActionEvent): Unit = {
    val now = System.currentTimeMillis

    if (isReady && ((now - scheduleTime > maxDelay) || (now - lastRunTime > minDelay))){
      timer.stop
      scheduleTime = 0
      action

    } else {
      lastRunTime = now
      // this is a repetitive timer, no need to reschedule
    }
  }

  def run: Unit = action
}
