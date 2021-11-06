/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, UnixPath}
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for NodeDates and related types
  */
class NodeDatesSpec extends AnyFlatSpec with RaceSpec {

  val input =
    """{
      "nodeDates": {
        "id": "/nodes/node_2",
        "columnDataDates": {
          "/columns/summary": 0,
          "/columns/column_1": 1593471960000,
          "/columns/column_4": 1593471964000
        },
        "columnDataRowDates": {
          "/columns/column_2": {
            "/data/cat_A": 1603752518526,
            "/data/cat_A/field_1": 1593471962500,
            "/data/cat_A/field_2": 1593471962000
          },
          "/columns/column_3": {
            "/data/cat_A/field_1": 1593471963000,
            "/data/cat_A/field_2": 1593471963000
          }
        }
      }
    }"""

  class NSParser extends BufferedStringJsonPullParser with NodeDatesParser

  "a NodeDatesParser" should "parse known json input" in {
    println(s"--- parsing NodeDates:\n$input\n---")
    val parser = new NSParser
    parser.initialize(input)
    val ns = parser.readNextObject {
      parser.readNextObjectMember(asc("nodeDates")) {
        parser.parseNodeDates().get
      }
    }
    println(ns)
    assert(ns.nodeId == "/nodes/node_2")
    assert(ns.columnDataDates.size == 3)
    assert(ns.columnDataRowDates.size == 2)
    println("Ok.")
  }
}
