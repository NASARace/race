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
import java.lang.Math._

/**
  * reg test for FHT1Interpolant
  */
class FHT1InterpolantSpec extends FlatSpec with RaceSpec with T1InterpolantTest {

  val ε: Double = 1e-3
  checkError = true

  @inline def rad(deg: Double): Double = deg * Math.PI / 180.0

  def gen (ts: Array[Long], vs: Array[Double]): T1Interpolant = new FHT1(ts,vs)
  def func (t: Long): Double = sin(rad(t))

  "a FHT1Interpolant" should "approximate known analytical functions at given points" in {
    println("--- point of sin(x)")
    testPoint(  Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180),  70)(func)(gen)
  }

  "a FHT1Interpolant" should "approximate forward intervals of analytical functions" in {
    println("--- forward interval of sin(x)")
    testRange(  Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180),  20,40,2)(func)(gen)
  }

  "a FHT1Interpolant" should "approximate backwards intervals of analytical functions" in {
    println("--- reverse interval of sin(x)")
    testReverseRange(  Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180),  40,20,2)(func)(gen)
  }

  "a FHT1Interpolant" should "provide a forward iterator" in {
    println("--- forward iterator for interpolated values for sin(x)")
    testForwardIterator(Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180), 20, 40, 2)(func)(gen)
  }

  "a FHT1Interpolant" should "provide a reverse iterator" in {
    println("--- reverse iterator for interpolated values for sin(x)")
    testReverseIterator(Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180), 40, 20, 2)(func)(gen)
  }

  /**
  "a FH1Interpolant" should "generate a interpolated vector" in {
    def f(t: Long): Double = sin(rad(t))

    println("--- array of interpolated values for sin(x)")
    val ts: Array[Long] = Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180)
    val vs: Array[Double] = ts.map(f)
    val r = new FHT1(ts, vs)
    val t0 = 20
    val t1 = 40
    val dt = 2
    val a = r.evalRange(t0,t1,dt)
    for ((v,i) <- a.zipWithIndex) {
      val t = t0 + (i * dt)
      val u = f(t)
      println(s"     $i:  r($t) = $v ($u -> e=${u - v})")
    }
  }

  "a FH1Interpolant" should "store head of interpolation results into a provided array" in {
    def f(t: Long): Double = sin(rad(t))

    println("--- head array of interpolated values for sin(x)")
    val ts: Array[Long] = Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180)
    val vs: Array[Double] = ts.map(f)
    val r = new FHT1(ts, vs)
    val a = new Array[Double](5)
    val t0 = 0
    val dt = 5
    val m = r.evalHeadInto(a, t0, 40, dt)
    for (i <- 0 until m) {
      val v = a(i)
      val t = t0 + (i * dt)
      val u = f(t)
      println(s"     $i:  r($t) = $v ($u -> e=${u - v})")
    }
  }

  "a FH1Interpolant" should "store tail of interpolation results into a provided array" in {
    def f(t: Long): Double = sin(rad(t))

    println("--- reverse tail array of interpolated values for sin(x)")
    val ts: Array[Long] = Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180)
    val vs: Array[Double] = ts.map(f)
    val r = new FHT1(ts, vs)
    val a = new Array[Double](5)
    val t1 = 90
    val dt = 5
    val m = r.evalReverseTailInto(a, 0, t1, dt)
    for (i <- 0 until m) {
      val v = a(i)
      val t = t1 - (i * dt)
      val u = f(t)
      println(s"     $i:  r($t) = $v ($u -> e=${u - v})")
    }
  }




  "a FHInterpolant" should "provide a reverse tail iterator" in {
    def f(t: Long): Double = sin(rad(t))

    println("--- reverse tail iterator for interpolated values for sin(x)")
    val ts: Array[Long] = Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180)
    val vs: Array[Double] = ts.map(f)
    val r = new FHT1(ts, vs)
    val it = r.reverseTailIterator(190, 100, 10)
    while (it.hasNext) {
      val p = it.next
      val t = p._1
      val v = p._2
      val u = f(t)
      println(s"       r($t) = $v ($u -> e=${u - v})")
    }
  }

    **/
}
