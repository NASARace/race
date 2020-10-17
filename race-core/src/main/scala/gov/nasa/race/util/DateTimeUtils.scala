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

package gov.nasa.race.util

import gov.nasa.race.uom.DateTime

import scala.concurrent.duration._
import scala.language.implicitConversions

/**
 * common functions related to time and durations
 */
object DateTimeUtils {

  val hhmmssRE = """(\d+):(\d+):(\d+)""".r

  val durationRE = """(\d+) *([hsm]|hour|hours|min|minute|minutes|sec|second|seconds|ms|msec|millis|millisecond|milliseconds|ns|nsec|nanos|nanosecond|nanoseconds)""".r

  // parses dtg groups such as "2016/03/18,13:02:44.458"

  val iso8601PeriodRE = """(P.+)""".r
  // <2do> too general
  val dateTimeRE = """(\d.+)""".r // everything that starts with a digit

  @inline def toHHMMSS(d: FiniteDuration): (Int, Int, Int) = (d.toHours.toInt, (d.toMinutes % 60).toInt, (d.toSeconds % 60).toInt)

  def timeBaseToMillis (tbase: String): Long = {
    tbase match {
      case "h" | "hour" | "hours" => 360000
      case "m" | "min" | "minute" | "minutes" => 60000
      case "s" | "sec" | "second" | "seconds" => 1000
      case "ms" | "msec" | "millis" | "millisecond" | "milliseconds" => 1
      case _ => throw new IllegalArgumentException(s"unsupported time base: $tbase")
    }
  }

  def durationMillis (spec: CharSequence): Long = {
    spec match {
      case durationRE(n,tbase) => n.toLong * timeBaseToMillis(tbase)
      case _ => Duration(spec.toString).toMillis
    }
  }

  def durationMillisToHMMSS (millis: Long): String = {
    val s = ((millis / 1000) % 60).toInt
    val m = ((millis / 60000) % 60).toInt
    val h = (millis / 3600000).toInt
    hmsToHMMSS(h,m,s)
  }

  def durationMillisToCompactTime (millis: Double): String = {
    if (millis.isInfinity || millis.isNaN) {
      ""
    } else {
      if (millis < 120000) f"${millis / 1000}%4.0fs"
      else if (millis < 360000) f"${millis / 60000}%4.1fm"
      else f"${millis / 360000}%4.1fh"
    }
  }

  def durationToHMMSS (d: FiniteDuration) = {
    hmsToHMMSS(d.toHours.toInt, (d.toMinutes % 60).toInt, (d.toSeconds % 60).toInt)
  }

  @inline private def setDD (c: Array[Char], idx: Int, d: Int): Unit = {
    c(idx) = (d / 10 + 48).toChar
    c(idx+1) = (d % 10 + 48).toChar
  }

  def hmsToHMMSS (h: Int, m: Int, s: Int): String = {
    if (h > 99) f"$h%d:$m%02d:$s%02d" else {
      val c = Array('0','0',':','0','0',':','0','0')
      setDD(c,0, h)
      setDD(c,3, m)
      setDD(c,6, s)
      new String(c)
    }
  }

  def asFiniteDuration(dur: scala.concurrent.duration.Duration): FiniteDuration = {
    if (dur.isFinite)
      FiniteDuration(dur.toMillis, MILLISECONDS)
    else
      throw new IllegalArgumentException(s"not a finite duration: $dur")
  }

  def fromNow (dur: FiniteDuration): DateTime = DateTime.now + dur

  def timeTag(d: FiniteDuration): Long = System.currentTimeMillis() / (d.toMillis)

  // Scala's Duration.create(s) is not permissive enough - it expects String input and only recognizes one unit
  val infRE = """[iI]nf|[nN]ever""".r
  val zeroRE = """[zZ]ero""".r

  val daysRE = """(\d+)d(?:$|\s|ay)""".r
  val hoursRE = """(\d+)h(?:$|\s|our)""".r
  val minutesRE = """(\d+)m(?:$|\s|in)""".r
  val secondsRE = """(\d+)s(?:$|\s|ec)""".r
  val millisRE = """(\d+)m(?:s$|s\s|sec|illi)""".r

  def parseDuration (durSpec: CharSequence): Duration = {
    var ns: Long = 0

    if (zeroRE.findFirstIn(durSpec).isDefined) return Duration.Zero
    if (infRE.findFirstIn(durSpec).isDefined) return Duration.Inf

    daysRE.findFirstMatchIn(durSpec).map(_.group(1)).foreach( ns += _.toLong * 86400000000000L)
    hoursRE.findFirstMatchIn(durSpec).map(_.group(1)).foreach( ns += _.toLong * 3600000000000L)
    minutesRE.findFirstMatchIn(durSpec).map(_.group(1)).foreach( ns += _.toLong * 60000000000L)
    secondsRE.findFirstMatchIn(durSpec).map(_.group(1)).foreach( ns += _.toLong * 1000000000L)
    millisRE.findFirstMatchIn(durSpec).map(_.group(1)).foreach( ns += _.toLong * 1000000L)

    Duration.fromNanos(ns)
  }

  //--- epoch dissection

  final val MsecPerDay = 1000*60*60*24
  final val MsecPerHour = 1000*60*60

  @inline def hourOfDay(t: Long): Int = (t % MsecPerDay).toInt / MsecPerHour
  @inline def hours (d: Long): Double = d.toDouble / MsecPerHour

}
