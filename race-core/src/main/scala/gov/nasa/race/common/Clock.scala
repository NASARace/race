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

package gov.nasa.race.common

import com.github.nscala_time.time.Imports._

import scala.concurrent.duration._


/**
  * time keeping utility that can be used to track simulation time, supporting
  * initial value and scaling
  * Note that we don't use vals since derived classes can change the values, and we don't use
  * public vars since each change should go through such a derived class
  */
class Clock (initTime: DateTime = DateTime.now, initTimeScale: Double = 1, isStopped: Boolean=false) {

  protected var _timeScale = initTimeScale
  protected var _base = initTime // sim time
  protected var initMillis = System.currentTimeMillis  // wall time
  protected var stoppedAt: Long = if (isStopped) initMillis else 0 // wall time

  def currentMillis = if (stoppedAt > 0) stoppedAt else System.currentTimeMillis

  def timeScale: Double = _timeScale
  def base = _base
  def baseMillis = _base.getMillis

  /** simulation time milliseconds */
  def millis: Long = _base.getMillis + ((currentMillis - initMillis) * _timeScale).toLong

  /** simulation time DateTime */
  def dateTime: DateTime = _base + ((currentMillis - initMillis) * _timeScale).toLong

  /** simulation time duration since initTime */
  def elapsed: FiniteDuration = ((currentMillis - initMillis) * _timeScale).toLong.milliseconds

  /** wallclock time duration since initTime */
  def elapsedWall: FiniteDuration = (currentMillis - initMillis).milliseconds

  /** wallclock time for given sim time */
  def wallTime (simTime: DateTime): DateTime = {
    DateTime.now.plusMillis(((simTime.getMillis - millis)/_timeScale).toInt)
  }

  /** wallclock time for given sim duration */
  def wallTimeIn (simDuration: FiniteDuration): DateTime = {
    DateTime.now.plusMillis((simDuration.toMillis/_timeScale).toInt)
  }
}

/**
  * a Clock that can be controlled at runtime, supporting reset and stop/resume
  */
class SettableClock (initTime: DateTime = DateTime.now,
                     initTimeScale: Double = 1,
                     isStopped: Boolean = false) extends Clock (initTime,initTimeScale,isStopped) {

  def reset (initTime: DateTime, initTimeScale: Double = 1): SettableClock = {
    _timeScale = initTimeScale
    _base = initTime
    initMillis = System.currentTimeMillis
    if (stoppedAt > 0) stoppedAt = initMillis
    this
  }

  def stop = stoppedAt = System.currentTimeMillis
  def resume = stoppedAt = 0
}
