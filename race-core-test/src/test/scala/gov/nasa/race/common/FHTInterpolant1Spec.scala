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
import java.lang.Math._

import gov.nasa.race.common.Nat.N1

/**
  * reg test for FHT1Interpolant
  */
class FHTInterpolant1Spec extends AnyFlatSpec with RaceSpec with TInterpolant1Test {

  val ε: Double = 1e-3
  checkError = true

  @inline def rad(deg: Long): Double = deg.toDouble * Math.PI / 180.0
  @inline def rad(deg: Double): Double = deg * Math.PI / 180.0

  def gen (ts: Array[Long], vs: Array[Double]): TInterpolant[N1,TDataPoint1] = new FHTInterpolant1(ts,vs)
  def func (t: Long): Double = sin(rad(t))

  "a FHTInterpolant1" should "approximate known analytical functions at given points" in {
    println("--- point of sin(x)")
    testPoint(  Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180),  70)(func)(gen)
  }

  "a FHTInterpolant1" should "approximate forward intervals of analytical functions" in {
    println("--- forward interval of sin(x)")
    testRange(  Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180),  20,40,2)(func)(gen)
  }

  "a FHTInterpolant1" should "approximate backwards intervals of analytical functions" in {
    println("--- reverse interval of sin(x)")
    testReverseRange(  Array(0, 20, 40, 60, 80, 100, 120, 140, 160, 180),  40,20,2)(func)(gen)
  }

  def checkLinearIteration (it: Iterator[TDataPoint1]): Unit = {
    while (it.hasNext){
      val p = it.next()
      println(p)
      p._0 shouldBe( p.millis * 10.0 +- 0.0000001)
    }
  }

  "a FHTInterpolant1 forward iterator" should "cover the correct end interval" in {
    println("--- forward iteration over end interval [5..8] of linear data model")

    val intr = new FHTInterpolant1(
      Array(0,  4,  6,  8),
      Array(0, 40, 60, 80)
    )
    checkLinearIteration(intr.iterator(5,8,1))
  }

  "a FHTInterpolant1 forward iterator" should "cover the correct start interval" in {
    println("--- forward iteration over start interval [0..5] of linear data model")

    val intr = new FHTInterpolant1(
      Array(0,  4,  6,  8),
      Array(0, 40, 60, 80)
    )
    checkLinearIteration(intr.iterator(0,5,1))
  }

  "a FHTInterpolant1 reverse iterator" should "cover the correct start interval" in {
    println("--- reverse iteration over start interval [0..5] of linear data model")

    val intr = new FHTInterpolant1(
      Array(0,  4,  6,  8),
      Array(0, 40, 60, 80)
    )
    checkLinearIteration(intr.reverseIterator(5,0,1))
  }
  "a FHTInterpolant1 reverse iterator" should "cover the correct end interval" in {
    println("--- reverse iteration over end interval [5..8] of linear data model")

    val intr = new FHTInterpolant1(
      Array(0,  4,  6,  8),
      Array(0, 40, 60, 80)
    )
    checkLinearIteration(intr.reverseIterator(8,5,1))
  }
}
