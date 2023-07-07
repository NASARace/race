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
import gov.nasa.race.util.FileUtils
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

  "a CsvPullParser" should "handle skipping fields" in {
    val input = "LSGC1,04/10/2023 03:32 UTC,,56.0,34.0,4.0,124.0,7.0,13.0,1.0,40.77,,7.0,53.0,7.7,149.0,27.84,,SE,"

    val p = new StringCsvPullParser {}
    if (p.initialize(input)) {
      p.skip(1)
      val f = p.readNextValue().toString
      println(s"f='$f'")
      assert(f == "04/10/2023 03:32 UTC")
    }
  }

  "a CsvPullParser" should "handle empty first fields" in {
    val input = ",42,,44"

    val p = new StringCsvPullParser {}
    if (p.initialize(input)) {
      val f1 = p.readNextValue().toString
      println(s"f1='$f1'")
      assert(f1 == "")
      val f2 = p.readNextValue().toString
      println(s"f2='$f2")
      assert(f2 == "42")
      p.skip(1)
      val f3 = p.readNextValue().toString
      println(s"f3='$f3")
      assert(f3 == "44")
    }
  }

  "a CsvPullParser" should "support skipping lines and fields" in {
    val idLineRE = "# STATION: (.+)".r
    val nameLineRE = "# STATION NAME: (.+)".r
    val latLineRE = "# LATITUDE: (.+)".r
    val lonLineRE = "# LONGITUDE: (.+)".r
    val elevLineRE = """# ELEVATION \[ft\]: (.+)""".r
    val stateLineRE = "# STATE: (.+)".r
    val dateSpecRE = """(\d{2})/(\d{2})/(\d{4}) *(\d{2}):(\d{2}) UTC""".r

    val data = FileUtils.fileContentsAsBytes(baseResourceFile("test.csv")).get
    val parser = new ByteCsvPullParser()
    if (parser.initialize(data)) {
      try {
        val id: String = parser.nextLine().flatMap(idLineRE.findFirstMatchIn(_)).map(_.group(1)).get
        val name: String = parser.nextLine().flatMap(nameLineRE.findFirstMatchIn(_)).map(_.group(1)).get
        val lat: Double = parser.nextLine().flatMap(latLineRE.findFirstMatchIn(_)).map(_.group(1).toDouble).get
        val lon: Double = parser.nextLine().flatMap(lonLineRE.findFirstMatchIn(_)).map(_.group(1).toDouble).get
        val elev: Double = parser.nextLine().flatMap(elevLineRE.findFirstMatchIn(_)).map(_.group(1).toDouble).get
        val state: String = parser.nextLine().flatMap(stateLineRE.findFirstMatchIn(_)).map(_.group(1)).get
        println(s"id=$id elev=$elev")
        assert(id == "LSGC1")
        assert(elev == 1842)

        parser.skipToNextRecord() // ignore field header
        parser.skipToNextRecord() // ignore uom header
        parser.skip(1) // ignore station id field
        parser.readNextValue().toString match {
          case dateSpecRE(mon,day,yr,hr,min) => assert(min.toInt == 32)
          case s => fail(s"wrong field: '$s'")
        }
        // .. we could loop over all entries of report to get the max minute but this seems to be invariant for each station
      } catch {
        case x: Throwable =>
          x.printStackTrace()
          fail("parser throws exception")
      }
    } else fail("parser did not initialize")
  }
}
