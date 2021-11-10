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

/**
  * simple benchmark for SIMD API
  */
object VecBenchmark {

  val nrounds = 50000

  def initIncreasing (a: Array[Double]): Unit = {
    var i=0
    while (i < a.length) {
      a(i) = i.toDouble
      i += 1
    }
  }

  import DoubleVec._

  def main (args: Array[String]): Unit = {
    var d1: Long = 0
    var d2: Long = 0

    // as of JDK 17.0.1 DoubleVector.fromArray crashes with N=10

    // warmup
    testAdd(10000, true)
    testAdd_SIMD(10000,true)

    println()
    d1 = testAdd(100,false)
    d2 = testAdd_SIMD(100)
    println(f"ratio for size    100: ${d2.toDouble/d1}%.2f")

    println()
    d1 = testAdd(1000)
    d2 = testAdd_SIMD(1000)
    println(f"ratio for size   1000: ${d2.toDouble/d1}%.2f")

    println()
    d1 = testAdd(100000)
    d2 = testAdd_SIMD(100000)
    println(f"ratio for size 100000: ${d2.toDouble/d1}%.2f")
  }

  def testAdd_SIMD (N: Int, quiet: Boolean=false): Long = {
    val vec = new DoubleVec

    val a = new Array[Double](N)
    val b = new Array[Double](N)
    val c = new Array[Double](N)

    initIncreasing(a)
    initIncreasing(b)

    System.gc()

    var i = 0
    val t1 = System.nanoTime()
    while (i < nrounds) {
      vec.compute(a, b)(c) { (va, vb) =>
        va + vb
      }
      i += 1
    }
    val t2 = System.nanoTime()
    val dt = t2 - t1

    if (!quiet) println(f"with vector:    $nrounds rounds with vector size: $N%6d : $dt%15d ns" )
    dt
  }

  def testAdd (N: Int, quiet: Boolean=false): Long = {
    val a = new Array[Double](N)
    val b = new Array[Double](N)
    val c = new Array[Double](N)

    initIncreasing(a)
    initIncreasing(b)

    var i = 0
    val t1 = System.nanoTime()
    while (i < nrounds) {
      var j = 0
      while (j < N) {
        c(j) = a(j) + b(j)
        j += 1
      }
      i += 1
    }
    val t2 = System.nanoTime()
    val dt = t2 - t1

    if (!quiet) println(f"without vector: $nrounds rounds with vector size: $N%6d : $dt%15d ns" )
    dt
  }
}
