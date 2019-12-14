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
package gov.nasa.race.air.gis

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for FixParser
  */
class FixParserSpec extends AnyFlatSpec with RaceSpec {

  "a FixParser" should "parse well formed Json with known data" in {
    val json = fileContentsAsUTF8String(baseResourceFile("fixes.json")).get
    val p = new FixParser

    val fixes = p.parse(json)

    fixes.foreach(println)

    assert(fixes.size == 10)
    assert(fixes.last.name == "AADEN")
    val aabee = fixes(4)
    assert(aabee.name == "AABEE")
    assert(aabee.pos.latDeg.round == 34)
    //assert(aabee.pos.toGenericString2D == "φ=+34.06806°,λ=-84.21436°")
    //.. and some more assertions to follow
  }
}
