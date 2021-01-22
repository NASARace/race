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
package gov.nasa.race.http.tabdata

import gov.nasa.race.common.{BufferedStringJsonPullParser, UnixPath}
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for NodeState and related types
  */
class NodeStateSpec extends AnyFlatSpec with RaceSpec {

  val input =
    """{
      "nodeState": {
        "id": "/providers/region1/provider_2",
        "rowListId": "/rows/sds1",
        "rowListDate": 1593370800000,
        "columnListId": "/providers/region1/provider_2",
        "columnListDate": 1593370800000,
        "readOnlyColumns": {
          "/providers/region1/summary": 0,
          "/providers/region1/provider_1": 1593471960000,
          "/providers/region1/provider_4": 1593471964000
        },
        "readWriteColumns": {
          "/providers/region1/provider_2": {
            "/data/cat_A": 1603752518526,
            "/data/cat_A/field_1": 1593471962500,
            "/data/cat_A/field_2": 1593471962000
          },
          "/providers/region1/provider_3": {
            "/data/cat_A/field_1": 1593471963000,
            "/data/cat_A/field_2": 1593471963000
          }
        }
      }
    }"""

  class NSParser extends BufferedStringJsonPullParser with NodeStateParser

  "a NodeStateParser" should "parse known json input" in {
    println(s"--- parsing NodeState:\n$input\n---")
    val parser = new NSParser
    parser.initialize(input)
    val ns = parser.readNextObject {
      parser.parseNodeState.get
    }
    println(ns)
    assert(ns.nodeId == "/providers/region1/provider_2")
    assert(ns.readOnlyColumns.size == 3)
    assert(ns.readWriteColumns.size == 2)
    println("Ok.")
  }
}
