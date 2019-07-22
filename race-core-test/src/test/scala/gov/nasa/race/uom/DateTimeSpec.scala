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

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import java.time.{ZonedDateTime}

/**
  * regression test for DateTimeSpec
  *
  * TODO - needs a lot more tests
  */
class DateTimeSpec extends AnyFlatSpec with RaceSpec {

  "DateTime" should "parse to the same epoch ms values as java.time" in {
    val specs = Array(
      "2019-07-19T11:53:04Z",   // no sec fraction
      "2019-07-19T11:53:04.42Z", // sec fraction
      "2019-07-19T11:53:04.123-07:00", // only offset
      "2019-07-19T11:53:04.123-09:00[US/Pacific]" // both zone id and offset, offset ignored
    )

    specs.foreach { spec =>
      println(s"--- $spec")

      val a = DateTime.parseYMDT(spec)
      val msa = a.toEpochMillis

      val b = ZonedDateTime.parse(spec)
      val msb = b.toInstant.toEpochMilli

      println(s"  dt  = $msa : $a")
      println(s"  zdt = $msb : $b")

      msa shouldBe msb
    }
  }
}
