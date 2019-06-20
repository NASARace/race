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
  * reg test for LinT1Interpolant
  */
class LinTInterpolant1Spec extends AnyFlatSpec with RaceSpec with TInterpolant1Test {

  val ε: Double = 1e-10
  checkError = true

  def gen (ts: Array[Long], vs: Array[Double]): TInterpolant[N1,TDataPoint1] = new LinTInterpolant1(ts,vs)
  def func1 (t: Long): Double = t * 0.5
  def func2 (t: Long): Double = if (t <= 10) 0.5*t else 5.0 + (t-10)

  "a LinTInterpolant1" should "approximate known analytical functions at given points" in {
    println("--- point of f(x) = 0.5*x")
    testPoint( Array(0, 10, 15, 18, 23, 30), 20)(func1)(gen)
  }

  "a LinTInterpolant1" should "approximate known analytical functions left of start point" in {
    println("--- point of f(x) = 0.5*x")
    testPoint( Array(10, 15, 18, 23, 30), 0)(func1)(gen)
  }

  "a LinTInterpolant1" should "approximate known analytical functions right of end point" in {
    println("--- point of f(x) = 0.5*x")
    testPoint( Array(10, 15, 18, 23, 30), 40)(func1)(gen)
  }

  "a LinTInterpolant1" should "approximate interval of known function" in {
    println("--- forward range of f(x) = 0.5*x")
    testRange( Array(10, 15, 20), 9, 11, 1)(func1)(gen)
  }

  "a LinTInterpolant1" should "approximate reverse interval of known function" in {
    println("--- reverse range of f(x) = 0.5*x")
    testReverseRange( Array(10, 15, 20), 21, 19, 1)(func1)(gen)
  }

}
