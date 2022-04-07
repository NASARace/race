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
package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for CsvPullParser
  */
class CsvPullParserSpec extends AnyFlatSpec with RaceSpec {

  "a CsvPullParser" should "parse some known values in single line input" in {

    // according to rfc4180 the last line does not have to be crlf terminated
    val input = "MSG,3,111,11111,A8BB0E,111111,2017/08/07,17:44:25.182,2017/08/07,17:44:25.234,,5525,,,37.81380,-121.56156,,,,,,0"

    val p = new StringCsvPullParser {}
    if (p.initialize(input)) {
      val v1 = p.readNextValue().toString
      assert(v1 == "MSG")
      p.skip(3)
      val v5 = p.readNextValue().toString
      assert(v5 == "A8BB0E")
      p.skip(9)
      val v15 = p.readNextValue().toDouble
      assert(v15 == 37.81380) // FIXME - don't compare floats
      p.skipToEndOfRecord()

      println(s"v1: $v1, v5: $v5, v15: $v15")

      assert(!p.parseNextValue())
      assert(!p.skipToNextRecord())
    }
  }


  "a CsvPullParser" should "parse known values in multi line input" in {
    val input =
      """MSG,3,111,11111,AB8B56,111111,2017/08/07,17:44:25.260,2017/08/07,17:44:25.300,,8950,,,37.48172,-121.80339,,,,,,0
        |MSG,3,111,11111,A1BFB2,111111,2017/08/07,17:44:25.261,2017/08/07,17:44:25.300,,18925,,,38.02153,-121.53100,,,,,,0
        |""".stripMargin

    println("--- input:")
    print(input)
    println("--- parsed:")

    val res = new StringBuilder()
    val p = new StringCsvPullParser {}
    if (p.initialize(input)) {
      while({   // do..while dropped in Scala3
        while (p.parseNextValue()){
          res.append(p.value)
          if (p.hasMoreValues) {
            res.append(',')
          }
        }
        res.append('\n')

        p.skipToNextRecord()
      })()
    }

    println(res)
    assert(res.toString() == input)
  }
}
