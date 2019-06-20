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
package gov.nasa.race.cl.comp

import gov.nasa.race.common.SmoothingExtrapolator
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.duration._

/**
  * unit test for BlockExtrapolator
  */
class BlockExtrapolatorSpec extends AnyFlatSpec with RaceSpec {

  "a BlockExtrapolator" should "produce the same results as a SmoothingExtrapolator" in {
    val N = 10
    val t = new Array[Long](N)
    val v = new Array[Double](N)

    for (i <- 0 until N) {
      t(i) = i
      v(i) = i
    }

    val se = new SmoothingExtrapolator(1.milliseconds,0.3,0.9)
    val be = new BlockExtrapolator(10, 1.milliseconds,  // max 10 entries with 2 state vars each
      Array[Double](0.0, 0.0),
      Array[Double](0.3, 0.3),
      Array[Double](0.9, 0.9)
    )
    val s = new Array[Double](2)

    for (i <- 0 until N) {
      se.addObservation(v(i), t(i))
      println(f"observation t=${t(i)} v=${v(i)}%.1f")

      s(0) = v(i)
      s(1) = v(i)
      be.addObservation("X", s, t(i))
      be.addObservation("Y", s, t(i))
    }

    assert (be.size == 2)
    assert (be.nStates == 2)

    val te = Array[Long](N+5,N+10)
    val ve = new Array[Double](te.length)
    val bve = new Array[Double](te.length)

    for (i <- 0 until te.length) {
      ve(i) = se.extrapolate(te(i))
      println(f"SE: t=${te(i)} v=${ve(i)}%.1f")

      be.extrapolate(te(i))

      var n = 0
      be.foreach { (id,a) =>
        if (n > 0) {
          assert(bve(i) == a(0))
        } else {
          bve(i) = a(0)
        }
        n += 1

        assert( a(0) == a(1))
        println(f"BE $id: v=${bve(i)}%.1f")
      }
      assert (n == be.size)
    }
  }
}
