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

import scalatags.generic.TypedTag
import scalatags.text.{Builder=>STBuilder}
import scalatags.Text.all._

/**
  * the generic container for statistics. This can be sent as a message, as long as
  * the snapshot element type is serializable
  */
trait Stats {
  val topic: String
  val takeMillis: Long       // absolute time value when stats snapshot was taken
  val elapsedMillis: Long    // duration covered by the snapshot
}

trait ConsoleStats extends Stats {
  def writeToConsole(pw: PrintWriter): Unit

  def consoleHeader: String = {
    val title = StringUtils.padRight(topic,60, ' ')
    val elapsed = StringUtils.padLeft(durationMillisToHMMSS(elapsedMillis), 20, ' ')
    s"$reverseColor$title          $elapsed$resetColor"
  }
}

trait FileStats extends Stats {
  def writeToFile(pw: PrintWriter): Unit
}

trait JsonStats extends Stats {
  def writeToJson(pw: PrintWriter): Unit
}

/**
  * a Stats type that knows how to produce HTML.
  *
  * We use a scalaTags specific TypedTag here because this ensures the generator
  * will produce HTML, and it avoids premature string allocation for fragments that
  * serve no other purpose than to be aggregated into complete HTML documents. The downside
  * is that we add a race-common dependency
  */
trait HtmlStats extends Stats {
  def toHtml: TypedTag[STBuilder,String,String]

  def htmlTopicHeader(channels: String) = h2(
    topic,
    span(style:="float:right;")(
      span(cls:="label")("channels:"),
      span(cls:="value")(channels),
      span(cls:="label")("elapsed:"),
      span(cls:="value")(durationMillisToHMMSS(elapsedMillis))
    )
  )
}
