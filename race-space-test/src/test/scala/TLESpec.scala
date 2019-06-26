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
package gov.nasa.race.space

import gov.nasa.race.test.RaceSpec
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * unit test for TLE
  */
class TLESpec extends AnyWordSpecLike with RaceSpec {

  "a TLE" should {
    "print identical to its input" in {
      val line0 = "ISS (ZARYA)"
      val line1 = "1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927"
      val line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537"

      println("-- input")
      println(line0)
      println(line1)
      println(line2)

      val tle = TLE(line0,line1,line2)
      println("-- object")
      println(tle)

      println("-- output")
      println(tle.line0)
      println(tle.line1)
      println(tle.line2)
    }
  }
}
