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

import scala.collection.mutable.Set

/**
  * reg test for MurmurHash64
  */
class MurmurHash64Spec extends AnyFlatSpec with RaceSpec {

  val flightIdx = Seq[String](
    "AA001", "AA002", "AA1001", "AA1111", "AA1",
    "SWA8762", "SWA8912", "SWA129", "SWA9999",
    "DAL7", "DAL70", "DAL700", "DAL7000"
  )

  val utfStrings = Seq[String](
    "abc",
    "I'm \u263b",  // smiley, 3 bytes
    "I'm \u263b \u00b6"  // paragraph, 2 bytes
  )

  val longStrings = Seq[String](
    "abc XYZ blabla hicks whatever",
    "I'm \u263b, or am I \u2639",  // smiley, 3 bytes
    "or I'm not \u263b but \uD83D\uDE36?"
  )

  "MurmurHash64" should "produce same hash values for known ascii data < 8 bytes(byte, char and string)" in {
    val seen = Set.empty[Long]

    println("--- ASCII data < 8 bytes")
    flightIdx.foreach { id =>
      val hBytes = MurmurHash64.hashBytes(id.getBytes)
      val hChars = MurmurHash64.hashChars(id.toCharArray)
      val hAscii = MurmurHash64.hashASCIIChars(id.toCharArray)
      val hStr   = MurmurHash64.hashString(id)

      println(s"'$id' : ($hBytes, $hChars, $hAscii $hStr)")

      assert (hBytes == hChars)
      assert (hChars == hAscii)   // flight ids are all ascii
      assert (hAscii == hStr)
      assert (!seen(hBytes))

      seen += hBytes
    }
  }

  "MurmurHash64" should "produce same hash values for UTF-16 data < 8 bytes" in {
    val seen = Set.empty[Long]

    println("--- UTF-16 data")
    utfStrings.foreach { s =>
      val hBytes = MurmurHash64.hashBytes(s.getBytes)
      val hChars = MurmurHash64.hashChars(s.toCharArray)
      val hStr   = MurmurHash64.hashString(s)

      println(s"'$s' : ($hBytes, $hChars, $hStr)")

      assert (hBytes == hChars)
      assert (hChars == hStr)   // flight ids are all ascii
      assert (!seen(hBytes))

      seen += hBytes
    }
  }

  "MurmurHash64" should "produce same hash values for UTF-16 data > 8 bytes" in {
    val seen = Set.empty[Long]

    println("--- UTF-16 data")
    longStrings.foreach { s =>
      val hBytes = MurmurHash64.hashBytes(s.getBytes)
      val hChars = MurmurHash64.hashChars(s.toCharArray)
      val hStr   = MurmurHash64.hashString(s)

      println(s"'$s' : ($hBytes, $hChars, $hStr)")

      assert (hBytes == hChars)
      assert (hChars == hStr)   // flight ids are all ascii
      assert (!seen(hBytes))

      seen += hBytes
    }
  }
}
