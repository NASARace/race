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
package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for CircularArray
  */
class CircularArraySpec extends AnyFlatSpec with RaceSpec {

  "a CircularArray" should "behave like a normal Seq if it does not wrap around" in {
    val n = 10
    println(s"--- no wrap around n = $n")

    val a = new CircularArray[Int](n)
    for (i <- 0 until n) a += i

    println(s"size = ${a.size}")
    assert( a.size == n)

    print("forward: ")
    val fwd = a.iterator
    for (i <- 0 to n-1) {
      assert(fwd.hasNext)
      val e = fwd.next()
      print(s"$e ")
      assert(e == i)
    }
    println()

    print("reverse: ")
    val rev = a.reverseIterator
    for (i <- Range.inclusive(n-1, 0, -1)) {
      assert(rev.hasNext)
      val e = rev.next()
      print(s"$e ")
      assert(e == i)
    }
    println()
  }

  "a CircularArray" should "store the last N items after a wrap around" in {
    val n = 5
    val m = 11
    println(s"--- wrap around n = $n, $m appends")

    val a = new CircularArray[Int](n)
    for (i <- 0 until m) a += i

    println(s"size = ${a.size}")
    assert( a.size == n)

    print("forward: ")
    var i = 6
    val fwd = a.iterator
    while (fwd.hasNext) {
      val e = fwd.next()
      print(f"$e%2d ")
      assert(e == i)
      i += 1
    }
    println()

    print("reverse: ")
    i = 10
    val rev = a.reverseIterator
    while (rev.hasNext) {
      val e = rev.next()
      print(f"$e%2d ")
      assert(e == i)
      i -= 1
    }
    println()
  }
}
