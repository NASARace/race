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
import org.scalatest.flatspec.AnyFlatSpec

/**
 * unit tests for generic RLEIterator
 */
class RLESeqSpec extends AnyFlatSpec with RaceSpec {

  "variable length RLESeq[Byte]" should "reproduce given values" in {
    val s = "1,2, -2,2, 3,3"
    val expected = Array[Byte](1,1,-2,-2,3,3,3)
    val rle = RLESeq.parseBytes(s)

    var i=0
    for (v <- rle) {
      println(s"next byte = $v")
      v shouldBe expected(i)
      i += 1
    }
    i shouldBe expected.length
    i shouldBe rle.length
  }

  "fixed length RLESeq[Float]" should "reproduce given values" in {
    val s = "1.42,2, -2.42,2, 3.42,1"
    val expected = Array[Float](1.42f,1.42f, -2.42f,-2.42f, 3.42f)
    val rle = RLESeq.parseFloats(s, 3)

    var i=0
    for (v <- rle) {
      println(s"next float = $v")
      v shouldBe expected(i)
      i += 1
    }
    i shouldBe expected.length
    i shouldBe rle.length
  }

}
