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
  * reg test for rational polynom interpolation
  */
class RatInterSpec extends FlatSpec with RaceSpec {

  @inline def rad(deg: Double): Double = deg * Math.PI / 180.0
  @inline def cot(r: Double): Double = 1d / Math.tan(r)

  "a TRatInter" should "approximate a const function" in {
    println("\n--- f(x) = C")
    val C: Double = 1.0
    val ts: Array[Int] = Array(0, 20, 40, 80)
    val vs: Array[Double] = new Array(ts.length)

    for ((a, i) <- ts.zipWithIndex) {
      vs(i) = C
    }

    val rat = new TRatInter(0, ts, vs)

    val t = 35
    val v = rat.interpolate(t)
    println(s"t=$t -> v=$v ($C)")
  }

  "a TRatInter" should "approximate a linear function" in {
    println("\n--- f(x) = x")
    val ts: Array[Int] = Array(0, 20, 40, 80)
    val vs: Array[Double] = new Array(ts.length)

    for ((a, i) <- ts.zipWithIndex) {
      vs(i) = a.toDouble
      //println(f"@@ $a%3d = ${vs(i)}%.1f")
    }

    val rat = new TRatInter(0, ts, vs)

    val t = 35
    val v = rat.interpolate(t)
    println(s"t=$t -> v=$v")
  }

  "a TRatInter" should "approximate a circular function" in {
    def circle (x: Double) = Math.sqrt(10000 - squared(x))

    println("\n--- f(x) = sqrt(R^2 - t^2) (circle)")
    val ts: Array[Int] = Array(0, 20, 40, 60, 80, 100)
    val vs: Array[Double] = new Array(ts.length)

    for ((t, i) <- ts.zipWithIndex) {
      vs(i) = circle(t)
      //println(f"@@ $t%3d = ${vs(i)}%.1f")
    }

    val rat = new TRatInter(0, ts, vs)

    val t = 35
    val v = rat.interpolate(t)
    val y = circle(t)
    println(f"$t%3d: $v%.2f ($y%.2f) e=${v - y}")
  }

  "a TRatInter" should "approximate a asymptotic function" in {
    def f (x: Double) = 1d - 1d / Math.exp(x)

    println("\n--- f(x) = 1 - 1/exp(t)")
    val ts: Array[Int] = Array(0, 1, 4, 5)
    val vs: Array[Double] = new Array(ts.length)

    for ((t, i) <- ts.zipWithIndex) {
      vs(i) = f(t)
      println(f"  $t%3d : ${vs(i)}%f")
    }

    val rat = new TRatInter(0, ts, vs)

    val t = 3
    val v = rat.interpolate(t)
    val y = f(t)
    println(f"$t%3d: $v%.8f ($y%.8f) e=${v - y}")
  }

  "a TRatInter" should "approximate a sin(x) function" in {  // TODO WRONG
    def f (x: Double): Double = Math.sin(x)

    println("\n--- f(x) = sin(x)")
    val ts: Array[Int] = Array(0, 45, 90, 135, 180)
    val vs: Array[Double] = new Array(ts.length)

    for ((a, i) <- ts.zipWithIndex) {
      vs(i) = f(rad(a))
      //println(f"@@ $a%3d : ${vs(i)}")
    }

    val rat = new TRatInter(0, ts, vs)

    val t = 30
    val v = rat.interpolate(t)
    println(s"t=$t -> v=$v (${f(rad(t))})")
  }

  "a DRatInter" should "approximate a cot(x) function" in {
    println("\n--- f(x) = cot(x)")
    val xs: Array[Double] = Array(1, 2, 3, 4, 5)
    val ys: Array[Double] = new Array(xs.length)

    for ((a, i) <- xs.zipWithIndex) {
      ys(i) = cot(rad(a))
      //println(f"@@ $a%.1f deg = ${ys(i)}%.8f")
    }

    val rat = new DRatInter(xs, ys)

    val x = 2.5
    val v = rat.interpolate(x)
    println(s"x=$x -> v=$v (${cot(rad(x))})")
  }
}
