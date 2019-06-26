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
package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * regression test for ThresholdLevel/List
  */
class ThresholdLevelSpec extends AnyFlatSpec with RaceSpec {

  var triggeredLevel: String = null

  def action(level: String): (String)=>Unit = (s: String) => {
    println(s"level $level triggered on object $s")
    triggeredLevel = level
  }

  "a ThresholdLevelList" should "always have a matching level" in {
    val tl = new ThresholdLevelList[String](action("Top"))

    // test single level list
    triggeredLevel = null

    println("-- trigger on TL that has only top level")
    tl.trigger(42, "A")
    assert(triggeredLevel == "Top")

    // test multi-level list
    val tBottom = new ThresholdLevel[String](42)(action("Bottom"))
    val tMiddle = new ThresholdLevel[String](84)(action("Middle"))
    tl.sortIn(tMiddle,tBottom)

    println("-- trigger on middle level of 3-TL")
    tl.trigger(60, "A")
    assert(triggeredLevel == "Middle")

    println("-- trigger on bottom level of 3-TL")
    tl.trigger(0, "A")
    assert(triggeredLevel == "Bottom")
  }

  "a ThresholdLevelList" should "remember the current level" in {
    val tl = new ThresholdLevelList[String](action("Top"))
    val tBottom = new ThresholdLevel[String](42)(action("Bottom"))
    tl.sortIn(tBottom)

    println("-- trigger without previous level")
    triggeredLevel = null
    tl.triggerInCurrentLevel("A")
    assert(triggeredLevel == null)

    println("-- trigger on top level")
    tl.trigger(50,"A")
    assert(triggeredLevel == "Top")

    println("-- trigger in current should be top level")
    tl.triggerInCurrentLevel("A")
    assert(triggeredLevel == "Top")

    println("-- set current to bottom and trigger current should be bottom level")
    val t = tl.getContainingLevel(10)
    t.action("A")
    assert(triggeredLevel == "Bottom")

  }
}
