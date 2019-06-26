/*
 * Copyright (c) 2016, United States Government, as represented by the
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
import gov.nasa.race.util.NumberTokenIterator
import org.scalatest.flatspec.AnyFlatSpec

/**
 * unit test for generic NumberTokenIterator utility
 *
 * <2do> add test for failures
 */
class NumberTokenIteratorSpec extends AnyFlatSpec with RaceSpec {

  "NumberTokenIterator[Int]" should "reproduce given input values with varying whitespace" in {
    val testData = """ 1,2, 3,  4  ,
                  5 """
    val tokenizer = new NumberTokenIterator[Int](testData)
    for (i <- 1 to 5) {
      val n: Int = tokenizer.next()
      println(s"$i: $n")
      n shouldBe i
    }
  }

  "NumberTokenIterator[Int]" should "reproduce correct number of given input values" in {
    val testData = "1,  2, 3, -4, 5"
    val expected = Array[Int](1,2,3,-4,5)
    val tokenizer = new NumberTokenIterator[Int](testData)

    var i=0
    for (v <- tokenizer) {
      println(s"next int = $v")
      v shouldBe expected(i)
      i += 1
    }
    i shouldBe expected.length
  }

  "NumberTokenIterator[Double]" should "reproduce correct number of given input values" in {
    val testData = "1,  2.0, -42.42, .3"
    val expected = Array[Double](1, 2.0, -42.42, .3)
    val tokenizer = new NumberTokenIterator[Double](testData)

    var i=0
    for (v <- tokenizer) {
      println(s"next double = $v")
      v shouldBe expected(i)
      i += 1
    }
    i shouldBe expected.length
  }
}
