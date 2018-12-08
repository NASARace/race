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
package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.FlatSpec

/**
  * reg test for CubicT1Spline
  */
class CubicT1SplineSpec extends FlatSpec with RaceSpec {

  "a CubicT1Spline" should "approximate known functions" in {
    {
      println("--- f(x) = x")
      val ts: Array[Int] = Array(0, 10, 20, 30, 40, 50)
      val vs: Array[Double] = Array(0d, 10d, 20d, 30d, 40d, 50d)

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(15, 25, 1) { (t, v) =>
        println(f"$t: $v%.1f")
        v.toInt shouldBe t
      }
    }

    {
      println("--- f(x) = C")
      val ts: Array[Int] = Array(0, 10, 20, 30, 40, 50)
      val vs: Array[Double] = Array(10d, 10d, 10d, 10d, 10d, 10d)

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(41, 60, 1) { (t, v) =>
        println(f"$t: $v%.1f")
        v shouldBe 10d
      }
    }

    {
      println("--- f(x) = sin(x)")
      val ts: Array[Int] = Array(0, 45, 90, 135, 180)
      val vs: Array[Double] = new Array(ts.length)

      for ((a, i) <- ts.zipWithIndex) {
        vs(i) = Math.sin((a.toDouble / 180.0) * Math.PI)
        //println(f"@@ $a%3d = ${vs(i)}%.1f")
      }

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(0, 180, 10) { (t, v) =>
        val y = Math.sin((t.toDouble / 180.0) * Math.PI)
        println(f"$t%3d: $v%.2f ($y%.2f) e=${v - y}")
        assert(Math.abs(v - y) < 0.01)
      }
    }

    {
      println("--- f(x) = sqrt(R^2 - t^2) (circle)")
      val ts: Array[Int] = Array(0, 20, 40, 60, 80, 100)
      val vs: Array[Double] = new Array(ts.length)

      for ((t, i) <- ts.zipWithIndex) {
        vs(i) = Math.sqrt(10000 - squared(t.toDouble))
        //println(f"@@ $t%3d = ${vs(i)}%.1f")
      }

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(0, 100, 10) { (t, v) =>
        val y = Math.sqrt(10000 - squared(t.toDouble))
        println(f"$t%3d: $v%.2f ($y%.2f) e=${v - y}")
        //assert(Math.abs(v - y) < 0.01)
      }
    }
  }
}
