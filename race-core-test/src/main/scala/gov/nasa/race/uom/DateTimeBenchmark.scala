/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.uom

import java.time.{Instant, ZonedDateTime}

import gov.nasa.race.test._


/**
  * benchmarking aspects of DateTime
  */
object DateTimeBenchmark {

  val runtime = Runtime.getRuntime

  //--- parsing

  // we measure time to get an Instant since that compares to DateTime, esp. with
  // respect to extracting epoch millis
  def parseJavaNanos (spec: String, expected: Long, nTimes: Long): Long = {
    var i = 0L
    var dt: Instant = null
    val t0: Long = System.nanoTime
    while (i < nTimes) {
      dt = ZonedDateTime.parse(spec).toInstant
      i += 1
    }
    val elapsed = System.nanoTime - t0
    if (dt.toEpochMilli != expected) throw new RuntimeException("wrong epoch value")
    elapsed
  }

  def parseDateTimeNanos (spec: String, expected: Long, nTimes: Long): Long = {
    var i = 0L
    var dt: DateTime = DateTime.UndefinedDateTime
    val t0: Long = System.nanoTime
    while (i < nTimes) {
      dt = DateTime.parseYMDT(spec)
      i += 1
    }
    val elapsed = System.nanoTime - t0
    if (dt.toInstant.toEpochMilli != expected) throw new RuntimeException("wrong epoch value")
    elapsed
  }

  def parseBenchmark: Unit = {
    val warmUp = 1000
    val nRounds = 1000000
    var nUsed: Long = 0
    var elapsed: Long = 0
    val spec = "2019-07-19T11:53:04.123-07:00"
    val expected: Long = ZonedDateTime.parse(spec).toInstant.toEpochMilli

    println(s"--- parsing $nRounds of '$spec'")

    Runtime.getRuntime.gc
    parseJavaNanos(spec,expected,warmUp)
    elapsed = parseJavaNanos(spec, expected, nRounds)
    println(s"  Java:     ${elapsed / 1000000} ms")

    Runtime.getRuntime.gc
    parseDateTimeNanos(spec,expected,warmUp)
    elapsed = parseDateTimeNanos(spec, expected, nRounds)
    println(s"  DateTime: ${elapsed / 1000000} ms")
  }


  //--- bench driver

  def main (args: Array[String]): Unit = {
    parseBenchmark
  }
}
