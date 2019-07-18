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
package gov.nasa.race.trajectory

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for LossyTrace
  */
class CompressedTraceSpec extends AnyFlatSpec with RaceSpec {

  "a CompressedTrace" should "store positions with 4-5 digits" in {

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

    val traj = new CompressedTrace(5)

    assert(traj.isEmpty)
    for (d <- data) traj.append(ofEpochMillis(d._1), Degrees(d._2), Degrees(d._3), Meters(d._4))
    assert(traj.size == traj.capacity)

    println("--- saturated CompressedTrace in reverse order of entry:")
    var i = data.length-1
    traj.foreachReverse(traj.newDataPoint) { p =>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")
      assert( p.millis == data(i)._1)
      p._0 shouldBe( data(i)._2 +- 0.00001)
      p._1 shouldBe( data(i)._3 +- 0.00001)
      p._2 shouldBe( data(i)._4 +- 0.5)
      i -= 1
    }
  }
}
