/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import java.io.PrintWriter

import gov.nasa.race.util.ConsoleIO.{resetColor, reverseColor}
import gov.nasa.race.util.DateTimeUtils.durationMillisToHMMSS
import gov.nasa.race.util.StringUtils


/**
  * the generic container for statistics. This can be sent as a message, as long as
  * the snapshot element type is serializable
  */
trait Stats extends Cloneable {
  val topic: String         // what we measure
  val source: String        // where we measure it from
  val takeMillis: Long      // absolute time value when stats snapshot was taken
  val elapsedMillis: Long   // duration covered by the snapshot
}

// we don't use polymorphism for the 

trait ConsoleStats extends Stats {
  def writeToConsole(pw: PrintWriter): Unit

  def writeHeaderToConsole (pw: PrintWriter) = {
    val title = StringUtils.padRight(s"$topic [$source]",60, ' ')
    val elapsed = StringUtils.padLeft(durationMillisToHMMSS(elapsedMillis), 20, ' ')

    pw.print(reverseColor)
    pw.print(title)
    pw.print("         ")
    pw.print(elapsed)
    pw.println(resetColor)
  }

  def consoleHeader: String = {
    val title = StringUtils.padRight(topic,60, ' ')
    val elapsed = StringUtils.padLeft(durationMillisToHMMSS(elapsedMillis), 20, ' ')
    s"$reverseColor$title          $elapsed$resetColor"
  }
}

trait FileStats extends Stats {
  def writeToFile(pw: PrintWriter): Unit

  def writeHeaderToFile (pw: PrintWriter) = {
    pw.println(topic)
    pw.println("========================================================================================")
    pw.print("elapsed: ")
    pw.println(durationMillisToHMMSS(elapsedMillis))
    pw.print("source:  ")
    pw.println(source)
    pw.println
  }
}

trait JsonStats extends Stats {
  def writeToJson(pw: PrintWriter): Unit
}

trait PrintStatsFormatter {
  def write (pw: PrintWriter, stats: Stats): Boolean // return true if stats were written
}

