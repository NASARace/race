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
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for CubicT1Spline
  */
class CubicTSplineSpec extends AnyFlatSpec with RaceSpec {

  @inline def rad(deg: Long): Double = deg.toDouble * Math.PI / 180.0
  @inline def rad(deg: Double): Double = deg * Math.PI / 180.0

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
        vs(i) = Math.sin(rad(a))
        //println(f"@@ $a%3d = ${vs(i)}%.1f")
      }

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(0, 180, 10) { (t, v) =>
        val y = Math.sin(rad(t))
        println(f"$t%3d: $v%.2f ($y%.2f) e=${v - y}")
        assert(Math.abs(v - y) < 0.01)
      }
    }

    {
      println("--- f(x) = cos(x)")
      val ts: Array[Int] = Array(0, 45, 90, 135, 180)
      val vs: Array[Double] = new Array(ts.length)

      for ((a, i) <- ts.zipWithIndex) {
        vs(i) = Math.cos(rad(a))
        //println(f"@@ $a%3d = ${vs(i)}%.1f")
      }

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(0, 180, 10) { (t, v) =>
        val y = Math.cos(rad(t))
        println(f"$t%3d: $v%.2f ($y%.2f) e=${v - y}")
        assert(Math.abs(v - y) < 0.035)  // error depends on f'
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

  "a CubicT1Spline" should "approximate with different step sizes" in {
    {
      val ts: Array[Int] = Array(0, 5, 8, 40, 45, 46, 90, 135, 180)
      val vs: Array[Double] = new Array(ts.length)
      println(s"--- f(x) = sin(x) at varying step size t=[${ts.mkString(",")}]")

      for ((a, i) <- ts.zipWithIndex) {
        vs(i) = Math.sin(rad(a))
        //println(f"@@ $a%3d = ${vs(i)}%.1f")
      }

      val s = new CubicT1Spline(0, ts, vs)

      s.evaluateFromTo(0, 180, 10) { (t, v) =>
        val y = Math.sin(rad(t))
        println(f"$t%3d: $v%.2f ($y%.2f) e=${v - y}")
        assert(Math.abs(v - y) < 0.01)
      }
    }
  }

  "a CubicT2Spline" should "approximate a 2D function" in {
    println("--- 2-dim curve interpolation: (x,y) = (sin(t),cos(t))")

    //val ts: Array[Int] = Array(0, 5, 8, 40, 45, 46, 90, 135, 180)
    val ts: Array[Int] = Array(0, 15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180)
    val xs: Array[Double] = new Array(ts.length)
    val ys: Array[Double] = new Array(ts.length)

    for ((a, i) <- ts.zipWithIndex) {
      val r = rad(a)
      xs(i) = Math.sin(r)
      ys(i) = Math.cos(r)
    }

    val s = new CubicT2Spline(0,ts,xs,ys)

    s.evaluateFromTo(0, 180, 10) { (t, x, y) =>
      val r = rad(t)
      val vx = Math.sin(r)
      val vy = Math.cos(r)
      println(f"$t%3d: [$x%5.2f , $y%5.2f]  e=[${vx - x} , ${vy - y}]")

      assert(Math.abs(vx - x) < 0.01)
      assert(Math.abs(vy - y) < 0.03)
    }
  }

  "a CubicT3Spline" should "approximate a 3D function" in {
    println("--- 3-dim curve interpolation: (x,y,z) = (sin(t),cos(t),sin(t))")

    //val ts: Array[Int] = Array(0, 5, 8, 40, 45, 46, 90, 135, 180)
    val ts: Array[Int] = Array(0, 15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180)
    val xs: Array[Double] = new Array(ts.length)
    val ys: Array[Double] = new Array(ts.length)
    val zs: Array[Double] = new Array(ts.length)

    for ((a, i) <- ts.zipWithIndex) {
      val r = rad(a)
      xs(i) = Math.sin(r)
      ys(i) = Math.cos(r)
      zs(i) = Math.sin(r)
    }

    val s = new CubicT3Spline(0,ts,xs,ys,zs)

    s.evaluateFromTo(0, 180, 10) { (t, x,y,z) =>
      val r = rad(t)
      val vx = Math.sin(r)
      val vy = Math.cos(r)
      val vz = vx
      println(f"$t%3d: [$x%5.2f , $y%5.2f , $z%5.2f]  e=[${vx - x} , ${vy - y}, ${vz - z}]")

      assert(Math.abs(vx - x) < 0.01)
      assert(Math.abs(vy - y) < 0.03)
      assert(x == z)
    }
  }
}
