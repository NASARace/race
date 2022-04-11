/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.test

import org.scalatest.flatspec.AnyFlatSpec

import java.io.ByteArrayInputStream

/**
  * reg test for ChunkInputStream
  */
class ChunkInputStreamSpec extends AnyFlatSpec with RaceSpec {

  "a ChunkInputStream" should "read known data" in {
    val input =
      """this is line 1
        |    and this line 2
        |which differs from line3 and all the other ones
        |  as you can see in line 4
        |up to the very long last line in our input, which happens to be this one. Period.""".stripMargin

    val is = new ChunkInputStream(new ByteArrayInputStream(input.getBytes()), 12, 42)

    val buf = new Array[Byte](64)
    val sb = new StringBuilder

    var nRead = is.read(buf)
    while (nRead >= 0) {
      val s = new String(buf,0,nRead)
      println(s"read $nRead bytes: '$s'")
      sb.append(s)
      nRead = is.read(buf)
    }

    assert(sb.toString == input)
  }
}
