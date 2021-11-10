/*
 * Copyright (c) 2021, United States Government, as represented by the
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
  * reg test for SIMD API
  */
class VecSpec extends AnyFlatSpec with RaceSpec {

  def initIncreasing (a: Array[Double]): Unit = {
    var i=0
    while (i < a.length) {
      a(i) = i.toDouble
      i += 1
    }
  }

  def printFirst (a: Array[Double], n: Int) = {
    var i = 0
    while (i < n) {
      print(f"${a(i)}%.0f,")
      i += 1
    }
    println("...")
  }

  "a DoubleVec" should "add" in {
    import DoubleVec._
    val N = 1000
    val vec = new DoubleVec

    val a = new Array[Double](N)
    val b = new Array[Double](N)
    val c = new Array[Double](N)

    initIncreasing(a)
    initIncreasing(b)

    println("-- adding double vectors with SIMD")
    vec.compute(a,b)(c) { (va,vb) =>
      va + vb
    }

    printFirst(c, 10)
    assert( c(0) == 0.0)
    assert( c(42) == 84.0)
  }

}
