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
  * reg test for LossLessTrace
  */
class NativeTrajectoryTraceSpec extends FlatSpec with RaceSpec {

  "an empty trace" should "not produce any values" in {
    val trace = new NativeTrajectoryTrace(4)
    trace.size shouldBe(0)

    trace.foreachPre { (i, t, lat, lon, alt) =>
      fail("entry in an empty trace")
    }
  }

  "a non-saturated trace" should "contain exact values of entries in right order" in {
    val data = Array(
      (0, 10.0, 20.0, 100.0),
      (1, 10.1, 20.1, 100.1),
      (2, 10.2, 20.2, 100.2)
    )

    val trace = new NativeTrajectoryTrace(4)
    for (d <- data) trace.addPre(d._1, d._2, d._3, d._4)

    assert(trace.size == data.length)

    println("--- un-saturated trace in order of entry:")
    trace.foreachPre{ (i, t, lat, lon, alt) =>
      println(s"$i: $t = ($lat, $lon, $alt)")
      assert( t == data(i)._1)
      assert( lat == data(i)._2)
      assert( lon == data(i)._3)
      assert( alt == data(i)._4)
    }

    println("--- un-saturated trace in reverse order of entry:")
    trace.foreachPreReverse{ (i, t, lat, lon, alt) =>
      println(s"$i: $t = ($lat, $lon, $alt)")
      assert( t == data(i)._1)
      assert( lat == data(i)._2)
      assert( lon == data(i)._3)
      assert( alt == data(i)._4)
    }
  }

  "a saturated trace" should "contain exact values of entries in right order" in {
    val data = Array(
      (0, 10.0, 20.0, 100.0),
      (1, 10.1, 20.1, 100.1),
      (2, 10.2, 20.2, 100.2),
      (3, 10.3, 20.3, 100.3),
      (4, 10.4, 20.4, 100.4),
      (5, 10.5, 20.5, 100.5)
    )

    val trace = new NativeTrajectoryTrace(4)
    for (d <- data) trace.addPre(d._1, d._2, d._3, d._4)

    assert(trace.size == trace.capacity)

    println("--- saturated trace in order of entry:")
    trace.foreachPre{ (i, t, lat, lon, alt) =>
      println(s"$i: $t = ($lat, $lon, $alt)")
      val j = data.length - trace.capacity + i
      assert( t == data(j)._1)
      assert( lat == data(j)._2)
      assert( lon == data(j)._3)
      assert( alt == data(j)._4)
    }

    println("--- saturated trace in reverse order of entry:")
    trace.foreachPreReverse{ (i, t, lat, lon, alt) =>
      println(s"$i: $t = ($lat, $lon, $alt)")
      val j = data.length - (trace.capacity - i)
      assert( t == data(j)._1)
      assert( lat == data(j)._2)
      assert( lon == data(j)._3)
      assert( alt == data(j)._4)
    }
  }
}
