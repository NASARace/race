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

import gov.nasa.race.uom.{DateTime,Time}

import scala.concurrent.duration._

object Clock {
  val wallClock = new Clock()
}

/**
  * time keeping utility that can be used to track simulation time, supporting
  * initial value and scaling
  * Note that we don't use vals since derived classes can change the values, and we don't use
  * public vars since each change should go through such a derived class
  */
class Clock (initTime: DateTime = DateTime.now,
             initTimeScale: Double = 1,
             endTime: DateTime = DateTime.UndefinedDateTime,
             stopped: Boolean=false)
         extends Cloneable {

  protected var _timeScale = initTimeScale
  protected var _base = initTime // sim time
  protected var _end = endTime

  protected var _initMillis = System.currentTimeMillis  // wall time
  protected var _stoppedAt: Long = if (stopped) _initMillis else 0 // wall time

  def currentMillis = if (_stoppedAt > 0) _stoppedAt else System.currentTimeMillis

  @inline def timeScale: Double = _timeScale

  // sim time
  def start: DateTime = _base
  def base: DateTime = _base
  def end: DateTime = _end

  // those are all wall clock millis
  def initMillis: Long = _initMillis
  @inline def stoppedAt: Long = _stoppedAt
  def baseMillis: Long = _base.toEpochMillis
  def endMillis: Long = endTimeMillis

  @inline def isStopped = _stoppedAt != 0


  /** simulation time milliseconds */
  @inline def millis: Long = _base.toEpochMillis + ((currentMillis - _initMillis) * _timeScale).toLong

  /** simulation time DateTime */
  def dateTime: DateTime = _base + Time.Milliseconds(((currentMillis - _initMillis) * _timeScale))

  /** simulation time duration since initTime */
  def elapsed: FiniteDuration = ((currentMillis - _initMillis) * _timeScale).toLong.milliseconds

  /** wallclock time duration since initTime */
  @inline def elapsedWall: FiniteDuration = (currentMillis - _initMillis).milliseconds

  /** wallclock time for given sim time */
  def wallTime (simTime: DateTime): DateTime = {
    DateTime.now + Time.Milliseconds(((simTime.toEpochMillis - millis)/_timeScale))
  }

  /** wallclock time for given sim duration */
  def wallTimeIn (simDuration: FiniteDuration): DateTime = {
    DateTime.now + Time.Milliseconds((simDuration.toMillis/_timeScale))
  }

  def wallEndTime: DateTime = _end.map(wallTime)

  def endTimeMillis: Long = if (_end.isDefined) _end.toEpochMillis else Long.MaxValue

  def exceedsEndTime (d: DateTime): Boolean = d.toEpochMillis > endTimeMillis

  def save = clone.asInstanceOf[Clock]
}

/**
  * a Clock that can be controlled at runtime, supporting reset and stop/resume
  */
class SettableClock (initTime: DateTime = DateTime.now,
                     initTimeScale: Double = 1,
                     endTime: DateTime = DateTime.UndefinedDateTime,
                     stopped: Boolean = false) extends Clock (initTime,initTimeScale,endTime,stopped) {

  def reset (initTime: DateTime, initTimeScale: Double = 1, endTime: DateTime = DateTime.UndefinedDateTime): SettableClock = synchronized {
    _timeScale = initTimeScale
    _base = initTime
    _end = endTime
    _initMillis = System.currentTimeMillis
    if (_stoppedAt > 0) _stoppedAt = _initMillis
    this
  }

  def reset (saved: Clock): SettableClock = synchronized {
    _timeScale = saved.timeScale
    _base = saved.base
    _end = saved.end
    _initMillis = saved.initMillis
    _stoppedAt = saved.stoppedAt
    this
  }

  def stop = synchronized {
    _stoppedAt = System.currentTimeMillis
  }

  def resume = synchronized {
    _initMillis += System.currentTimeMillis - _stoppedAt
    _stoppedAt = 0
  }
}
