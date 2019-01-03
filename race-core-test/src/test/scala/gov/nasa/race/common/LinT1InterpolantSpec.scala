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
  * reg test for LinT1Interpolant
  */
class LinT1InterpolantSpec extends FlatSpec with RaceSpec with T1InterpolantTest {

  val ε: Double = 1e-10
  checkError = true

  def gen (ts: Array[Long], vs: Array[Double]): T1Interpolant = new LinT1(ts,vs)
  def func1 (t: Long): Double = t * 0.5
  def func2 (t: Long): Double = if (t <= 10) 0.5*t else 5.0 + (t-10)

  "a LinT1Interpolant" should "approximate known analytical functions at given points" in {
    println("--- point of f(x) = 0.5*x")
    testPoint( Array(0, 10, 15, 18, 23, 30), 20)(func1)(gen)
  }

  "a LinT1Interpolant" should "approximate known analytical functions left of start point" in {
    println("--- point of f(x) = 0.5*x")
    testPoint( Array(10, 15, 18, 23, 30), 0)(func1)(gen)
  }

  "a LinT1Interpolant" should "approximate known analytical functions right of end point" in {
    println("--- point of f(x) = 0.5*x")
    testPoint( Array(10, 15, 18, 23, 30), 40)(func1)(gen)
  }

  "a LinT1Interpolant" should "approximate interval of known function" in {
    println("--- forward range of f(x) = 0.5*x")
    testRange( Array(10, 15, 20), 9, 11, 1)(func1)(gen)
  }

  "a LinT1Interpolant" should "approximate reverse interval of known function" in {
    println("--- reverse range of f(x) = 0.5*x")
    testReverseRange( Array(10, 15, 20), 21, 19, 1)(func1)(gen)
  }

  "a LinT1Interpolant iterator" should "approximate interval of known function" in {
    println("--- forward iterator of f(x) = 0.5*x")
    testForwardIterator( Array(10, 15, 20), 9, 11, 1)(func1)(gen)
  }

  "a LinT1Interpolant reverse iterator" should "approximate interval of known function" in {
    println("--- reverse iterator of f(x) = 0.5*x")
    testReverseIterator( Array(10, 15, 20), 21, 19, 1)(func1)(gen)
  }

  "a LinT1Interpolant" should "approximate a known piece-wise function over full range" in {
    println("--- forward range of f(x) = if (x <= 10) 0.5*x else 5 + (x-10)")
    testRange( Array(8, 10, 12), 7, 13, 1)(func2)(gen)
  }

  "a LinT1Interpolant forward iterator" should "approximate a known piece-wise function over full range" in {
    println("--- forward iterator of f(x) = if (x <= 10) 0.5*x else 5 + (x-10)")
    testForwardIterator( Array(8, 10, 12), 7, 13, 1)(func2)(gen)
  }

  "a LinT1Interpolant" should "approximate a known piece-wise function over full reverse range" in {
    println("--- reverse range of f(x) = if (x <= 10) 0.5*x else 5 + (x-10)")
    testReverseRange( Array(8, 10, 12), 13, 7, 1)(func2)(gen)
  }

  "a LinT1Interpolant reverse iterator" should "approximate a known piece-wise function over full range" in {
    println("--- reverse iterator of f(x) = if (x <= 10) 0.5*x else 5 + (x-10)")
    testReverseIterator( Array(8, 10, 12), 13, 7, 1)(func2)(gen)
  }
}
