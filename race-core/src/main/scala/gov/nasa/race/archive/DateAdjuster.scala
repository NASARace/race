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

package gov.nasa.race.archive

import gov.nasa.race.uom.Time.Milliseconds
import gov.nasa.race.uom.{DateTime, Time}

import scala.concurrent.duration.FiniteDuration

/**
  * a trait for objects that can adjust date based on delta between a original (first)
  * date and a dynamic base date
  *
  * primary use case are objects that have to provide some replay capability in a changed time
  * context
  */
trait DateAdjuster {
  protected var dateOffset: Time = Time.Time0
  protected var baseDate: DateTime = DateTime.UndefinedDateTime
  protected var firstDate: DateTime = DateTime.UndefinedDateTime

  // NOTE - this is a snapshot
  def asOptionalAdjuster: Option[DateAdjuster] = if (isAdjustingDates) Some(this) else None

  def isAdjustingDates: Boolean = baseDate.isDefined || dateOffset.nonZero
  def isRebasingDates: Boolean = baseDate.isDefined

  def setDateOffsetMillis (ms: Long): Unit = dateOffset = Milliseconds(ms)
  def getDateOffsetMillis: Long = dateOffset.toMillis
  def setDateOffset (dur: FiniteDuration): Unit = setDateOffsetMillis(dur.toMillis)

  def setBaseDate(date: DateTime): Unit = baseDate = date
  def getBaseDate: DateTime = baseDate

  /**
    * get potentially adjusted date
    */
  def getDate (date: DateTime): DateTime = {
    if (baseDate.isUndefined) {
      date + dateOffset
    } else {
      if (firstDate.isUndefined) {
        firstDate = date
        baseDate + dateOffset
      } else {
        if (date > firstDate) {
          baseDate + firstDate.timeUntil(date) + dateOffset
        } else {
          baseDate - firstDate.timeSince(date) + dateOffset
        }
      }
    }
  }
}
