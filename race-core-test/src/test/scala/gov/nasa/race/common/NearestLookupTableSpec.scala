/*
 * Copyright (c) 2022, United States Government, as represented by the
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
 * reg test for NearestLookupTable
 */
class NearestLookupTableSpec extends AnyFlatSpec with RaceSpec {

  "a NearestLookupTable" should "find find correct matches in known input data" in {
    val elems = Seq( (5,"five"), (9,"nine"), (4, "four"), (15,"foftein"))
    println(elems)

    val nlt = NearestLookupTable.from(elems)

    var t = 8
    var res = nlt.findLessOrEqual( t)
    println(s"lowerEqual($t) -> $res")
    assert( res._1 == 5)

    try {
      nlt.findLessOrEqual(3)
      fail("failed to catch <= out-of-range")
    } catch {
      case x:NoSuchElementException => println(s"caught $x")
    }

    try {
      nlt.findGreaterOrEqual(16)
      fail("failed to catch >= out-of-range")
    } catch {
      case x:NoSuchElementException => println(s"caught $x")
    }

    res = nlt.findGreaterOrEqual(t)
    println(s"upperEqual($t) -> $res")
    assert( res._1 == 9)

    t = 10
    res = nlt.findNearest(t)
    println(s"nearest($t) -> $res")
    assert( res._1 == 9)

    t = 15
    res = nlt.findGreaterOrEqual(t)
    println(s"upper($t) -> $res")
    assert( res._1 == 15)

    val withinRange = nlt.isWithinRange(t)
    println(s"withinRange($t): $withinRange")
    assert(withinRange)
  }
}
