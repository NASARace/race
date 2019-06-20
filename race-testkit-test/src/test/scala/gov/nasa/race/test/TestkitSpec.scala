/*
 * Copyright (c) 2016, United States Government, as represented by the
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
package gov.nasa.race.test

import org.scalatest.flatspec.AnyFlatSpec
import java.util.{List =>JList}

/**
  * test suite for RACE test infrastructure
  */
class TestkitSpec  extends AnyFlatSpec with RaceSpec {

  "a RACE test" should "find qualified resource files" in {
    val rf = qualifiedResourceFile("testResource")
    println(s"    found resourceFile: $rf")
  }

  "a RACE test" should "find base resource files" in {
    val rf = baseResourceFile("testConfig.conf")
    println(s"    found resourceFile: $rf")
  }

  "a RACE test" should "load base configs from resource files" in {
    val conf = baseResourceConfig("testConfig.conf")

    val actors: JList[_] = conf.getObjectList("universe.actors")
    if (actors.size == 2) println(s"    found 2 elements in 'universe.actors': $actors")
    else fail("missing or wrong config object 'universe.actors'")

    val internalUri = conf.getString("universe.activemq.export.internal-uri")
    if (internalUri == "vm://localhost") println(s"    found universe.activemq.export.internal-uri = $internalUri")
    else fail("missing or wrong config value for universe.activemq.export.internal-uri")
  }

  "a RACE test" should "load qualified configs from resource files" in {
    val conf = qualifiedResourceConfig("amq.conf")
    val internalUri = conf.getString("activemq.export.internal-uri")
    if (internalUri == "vm://localhost") println(s"    found activemq.export.internal-uri = $internalUri")
    else fail("missing or wrong config value for activemq.export.internal-uri")
  }
}
