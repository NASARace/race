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
import org.scalatest.FlatSpec
import scala.concurrent.duration._

/**
  * unit test for BlockExtrapolator
  */
class BlockExtrapolatorSpec extends FlatSpec with RaceSpec {

  "a BloclExtrapolator" should "produce the same results as a SmoothingExtrapolator" in {
    val N = 10
    val t = new Array[Long](N)
    val v = new Array[Double](N)

    for (i <- 0 until N) {
      t(i) = i
      v(i) = i
    }

    val se = new SmoothingExtrapolator(1.milliseconds,0.3,0.9)
    val be = new BlockExtrapolator(1, 1.milliseconds,
      Array[Double](0),
      Array[Double](0.3),
      Array[Double](0.9)
    )
    val a = new Array[Double](1)

    for (i <- 0 until N) {
      se.addObservation(v(i), t(i))
      println(f"observation t=${t(i)} v=${v(i)}%.1f")

      a(0) = v(i)
      be.addObservation("X", a, t(i))
    }

    val te = Array[Long](N+5,N+10)
    val ve = new Array[Double](te.length)
    val bve = new Array[Double](te.length)

    for (i <- 0 until te.length) {
      ve(i) = se.extrapolate(te(i))
      println(f"SE: t=${te(i)} v=${ve(i)}%.1f")

      be.extrapolate(te(i))
      be.foreach { a => bve(i) = a(0) }
      println(f"BE: v=${bve(i)}%.1f")
    }
  }
}
