/*
 * Copyright (c) 2019, United States Government, as represented by the
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
  * reg test for UTF8 support functions
  */
class UTF8Spec extends AnyFlatSpec with RaceSpec {

  val ts = Array(
    "abc",
    "I'm \u263b",  // smiley, 3 bytes
    "I'm \u263b \u00b6"  // paragraph, 2 bytes
  )

  "UTF8.utf8Length" should "compute the right UTF-8 length for Strings" in {
    ts.foreach { s=>
      val len = UTF8.utf8Length(s)
      len shouldBe s.getBytes.length
      println(s"$s : $len")
    }
  }

  "UTF8.Encoder" should "properly encode Array[Char]" in {
    ts.foreach { s =>
      val cs = s.toCharArray
      val bs = s.getBytes
      val bss = bs.map( b=> (b.toInt & 0xff).toHexString).mkString(",")

      println(s"--- '$s' (${bs.length} : $bss)")

      var j = 0
      var utf8 = UTF8.initEncoder(cs)
      while (!utf8.isEnd) {
        val b = utf8.utf8Byte
        print(f"$b%x , state: ")
        utf8.print
        assert(b == bs(j))
        j += 1
        utf8 = utf8.next(cs,cs.length)
      }
    }
  }
}
