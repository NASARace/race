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
import org.joda.time.DateTime
import org.joda.time.format.ISOPeriodFormat

import scala.concurrent.duration._
import scala.language.implicitConversions

/**
 * common functions related to time and durations
 */
object DateTimeUtils {

  val hhmmssRE = """(\d+):(\d+):(\d+)""".r

  // parses dtg groups such as "2016/03/18,13:02:44.458"

  val iso8601PeriodRE = """(P.+)""".r
  // <2do> too general
  val isoPeriodFormatter = ISOPeriodFormat.standard
  val dateTimeRE = """(\d.+)""".r // everything that starts with a digit

  val MMddyyyyhhmmssZ = DateTimeFormat.forPattern("MM/dd/yyyy hh:mm:ss Z")
  val hhmmssZ = DateTimeFormat.forPattern("hh:mm:ss Z")
  val hhmmss = DateTimeFormat.forPattern("hh:mm:ss")

  def toHHMMSS(d: FiniteDuration): (Int, Int, Int) = (d.toHours.toInt, (d.toMinutes % 60).toInt, (d.toSeconds % 60).toInt)

  // Duration is another Java/Scala quagmire - we have them in java.time,
  // scala.concurrent.duration and org.joda.time. To make matters worse,
  // the scala durations come in two flavors: Duration and FiniteDuration, which
  // are both instantiable

  def duration(hh: Int, mm: Int, ss: Int) = {
    new org.joda.time.Duration(hh * 3600000000L + mm * 3600000 + ss * 1000)
  }

  def asFiniteDuration(dur: scala.concurrent.duration.Duration): FiniteDuration = {
    if (dur.isFinite())
      FiniteDuration(dur.toMillis, MILLISECONDS)
    else
      throw new IllegalArgumentException(s"not a finite duration: $dur")
  }

  def fromNow (dur: FiniteDuration): DateTime = DateTime.now.plusMillis(dur.toMillis.toInt)

  def timeTag(d: FiniteDuration): Long = System.currentTimeMillis() / (d.toMillis)
}
