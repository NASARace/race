/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.track

import gov.nasa.race.test.RaceSpec
import org.scalatest.FlatSpec

/**
  * reg test for Trace
  */
class TraceSpec extends FlatSpec with RaceSpec {

  "an empty trace" should "not produce any values" in {
    val trace = new Trace(4)
    trace.size shouldBe(0)

    trace.foreach { (i, lat, lon, alt, t) =>
      fail("entry in an empty trace")
    }
  }

  "a non-saturated trace" should "contain exact values of entries in right order" in {
    val data = Array(
      (10.0, 20.0, 100.0, 1),
      (10.1, 20.1, 100.1, 2),
      (10.2, 20.2, 100.2, 3)
    )

    val trace = new Trace(4)
    for (d <- data) trace.add(d._1, d._2, d._3, d._4)

    assert(trace.size == data.length)

    println("--- un-saturated trace in order of entry:")
    trace.foreach{ (i, lat,lon,alt,t) =>
      println(s"$i: ($lat, $lon, $alt, $t)")
      assert( lat == data(i)._1)
      assert( lon == data(i)._2)
      assert( alt == data(i)._3)
      assert( t == data(i)._4)
    }

    println("--- un-saturated trace in reverse order of entry:")
    trace.foreachReverse{ (i, lat,lon,alt,t) =>
      println(s"$i: ($lat, $lon, $alt, $t)")
      assert( lat == data(i)._1)
      assert( lon == data(i)._2)
      assert( alt == data(i)._3)
      assert( t == data(i)._4)
    }
  }

  "a saturated trace" should "contain exact values of entries in right order" in {
    val data = Array(
      (10.0, 20.0, 100.0, 0),
      (10.1, 20.1, 100.1, 1),
      (10.2, 20.2, 100.2, 2),
      (10.3, 20.3, 100.3, 3),
      (10.4, 20.4, 100.4, 4),
      (10.5, 20.5, 100.5, 5)
    )

    val trace = new Trace(4)
    for (d <- data) trace.add(d._1, d._2, d._3, d._4)

    assert(trace.size == trace.capacity)

    println("--- saturated trace in order of entry:")
    trace.foreach{ (i, lat,lon,alt,t) =>
      println(s"$i: ($lat, $lon, $alt, $t)")
      val j = data.length - trace.capacity + i
      assert( lat == data(j)._1)
      assert( lon == data(j)._2)
      assert( alt == data(j)._3)
      assert( t == data(j)._4)
    }

    println("--- saturated trace in reverse order of entry:")
    trace.foreachReverse{ (i, lat,lon,alt,t) =>
      println(s"$i: ($lat, $lon, $alt, $t)")
      val j = data.length - (trace.capacity - i)
      assert( lat == data(j)._1)
      assert( lon == data(j)._2)
      assert( alt == data(j)._3)
      assert( t == data(j)._4)
    }
  }
}
