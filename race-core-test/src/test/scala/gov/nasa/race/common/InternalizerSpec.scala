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
  * reg tests for Internalizers
  */
class InternalizerSpec extends AnyFlatSpec with RaceSpec {

  val ascii8Data = Seq[String](
    "AA001", "AA002", "AA1001", "AA1111", "AA1",
    "SWA8762", "SWA8912", "SWA129", "SWA9999",
    "DAL7", "DAL70", "DAL700", "DAL7000"
  )

  def ref (o: Any): String = System.identityHashCode(o).toHexString

  "ASCII8Internalizer" should "guarantee string identity" in {
    println("--- internalize ASCII data < 8 bytes")
    ascii8Data.foreach { s =>
      val bs = s.getBytes
      val cs = s.toCharArray

      val sBytes = ASCII8Internalizer.get(bs,0,bs.length)
      val sChars =  ASCII8Internalizer.get(cs,0,cs.length)
      val sString =  ASCII8Internalizer.get(s)

      println(s"'$s' -> '$sBytes'(${ref(sBytes)}), '$sChars'(${ref(sChars)}), '$sString'(${ref(sString)})," )
      assert(sBytes eq sChars)
      assert(sChars eq sString)
    }

    //assert(ASCII8Internalizer.size == ascii8Data.size) // this breaks when executing tests concurrently
  }

  val longStrings = Seq[String](
    "abc blabla har bar",
    "tell me if I'm \u263b",  // smiley, 3 bytes
    "or I'm not \u263b but \uD83D\uDE36?"
  )

  "Internalizer" should "guarantee string identity" in {
    println("--- internalize general UTF data > 8 bytes")
    longStrings.foreach { s =>
      val bs = s.getBytes
      val cs = s.toCharArray

      val sBytes = Internalizer.get(bs,0,bs.length)
      val sChars =  Internalizer.get(cs,0,cs.length)
      val sString =  Internalizer.get(s)

      println(s"'$s' -> '$sBytes'(${ref(sBytes)}), '$sChars'(${ref(sChars)}), '$sString'(${ref(sString)})" )
      assert(sBytes eq sChars)
      assert(sChars eq sString)
    }

    //assert(Internalizer.size == longStrings.size)  // this breaks when executing tests concurrently
  }

  "Internalizer" should "work with UTF8Buffers" in {
    println("--- internalize from ASCIIBuffer")
    val buf = new AsciiBuffer(32)
    buf += "ZAP"
    buf += '-'
    buf += "123"
    val s1 = ASCII8Internalizer.get(buf)
    val s2 = ASCII8Internalizer.get("ZAP-123")
    println(f"'$s1' (${System.identityHashCode(s1)}%x) == '$s2' (${System.identityHashCode(s2)}%x)")
    assert( s1 eq s2)
  }
}
