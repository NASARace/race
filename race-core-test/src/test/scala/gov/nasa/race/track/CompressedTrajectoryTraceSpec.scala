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
  * reg test for LossyTrace
  */
class CompressedTrajectoryTraceSpec extends FlatSpec with RaceSpec {

  "a LossyTrace" should "store positions with 4-5 digits" in {

    val data = Array(
      (0, 37.62000, -122.38000, 3000.0),
      (1, 37.62001, -122.38001, 3001.0),
      (2, 37.62002, -122.38002, 3002.0),
      (3, 37.62003, -122.38003, 3003.0),
      (4, 37.62004, -122.38004, 3004.0),
      (5, 37.62005, -122.38005, 3005.0),
      (6, 37.62006, -122.38006, 3006.0),
      (7, 37.62007, -122.38007, 3007.0),
      (8, 37.62008, -122.38008, 3008.0),
    )

    val t = new CompressedTrajectoryTrace(5)

    assert(t.isEmpty)
    for (d <- data) t.addPre(d._1, d._2, d._3, d._4)
    assert(t.size == t.capacity)

    println("--- saturated lossy trace in reverse order of entry:")
    var i = data.length-1
    t.foreachPreReverse { (_, t, lat, lon, alt) =>
      println(f"$i: $t = ($lat%10.5f, $lon%10.5f, $alt%5.0f)")
      assert( t == data(i)._1)
      lat shouldBe( data(i)._2 +- 0.00001)
      lon shouldBe( data(i)._3 +- 0.00001)
      alt shouldBe( data(i)._4 +- 0.5)
      i -= 1
    }
  }
}
