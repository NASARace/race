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
  * reg test for StringDataBuffer implementations
  */
class StringDataBufferSpec extends AnyFlatSpec with RaceSpec {

  "an ASCIIBuffer" should "accumulate known ASCII data" in {
    val buf = new AsciiBuffer(64)
    buf += "one"
    buf += '-'
    buf += "two"

    println(s"--- ASCIIBuffer append: '${new String(buf.data,0,buf.len)}' : ${buf.len}")
    assert(buf.len == 7)
  }

  "a UTF8Buffer" should "accumulate known ASCII data" in {
    val buf = new Utf8Buffer(64)
    buf += "one"
    buf += '-'
    buf += "two"

    println(s"--- UTF8Buffer append: '${new String(buf.data,0,buf.len)}' : ${buf.len}")
    assert(buf.len == 7)
  }

  "a UTF8Buffer" should "reproduce known unicode strings" in {
    val sIn = "I'm \u263b \u00b6"

    val buf = new Utf8Buffer(64)
    buf.encode(sIn)

    val sOut = new String(buf.data,0,buf.len)

    println(s"--- UTF8Buffer.encode: '$sIn' -> '$sOut' : ${buf.len}")
    assert (sIn == sOut)
  }
}
