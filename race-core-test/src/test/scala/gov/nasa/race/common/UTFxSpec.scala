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
class UTFxSpec extends AnyFlatSpec with RaceSpec {

  val ts = Array(
    //"abc",
    //"I'm \u263b",  // smiley, 3 bytes
    //"I'm \u263b \u00b6",  // paragraph, 2 bytes
    "I'm \u263b, or am I \uD83D\uDE36 ?"  // surrogate pair, 4 bytes/2 chars
  )

  "UTF8.utf8Length" should "compute the right UTF-8 length for Strings" in {
    ts.foreach { s=>
      val len = UTFx.utf8Length(s)
      println(s"'$s' : utf-8 length = $len")
      len shouldBe s.getBytes.length
    }
  }

  "UTF8.Encoder" should "properly utf-8 encode Array[Char]" in {
    ts.foreach { s =>
      val cs = s.toCharArray
      val bs = s.getBytes
      val css = cs.map( b=> (b.toInt & 0xffff).toHexString).mkString(",")
      val bss = bs.map( b=> (b.toInt & 0xff).toHexString).mkString(",")


      println(s"--- encode utf-8 for '$s'")
      println(s"       => utf-16: (${cs.length} : $css)")
      println(s"       => utf-8:  (${bs.length} : $bss)")

      var j = 0
      var encoder = UTFx.initUTF8Encoder(cs)
      while (!encoder.isEnd) {
        val b = encoder.utf8Byte
        print(f"$b%x , state: ")
        encoder.print
        assert(b == bs(j))
        j += 1
        encoder = encoder.next(cs,cs.length)
      }
      j shouldBe bs.length
    }
  }

  "UTF8.Encoder" should "properly utf-8 encode single non-surrogate pair char" in {
    def checkLength (c: Char, expected: Int): Boolean = {
      val l = UTFx.utf8Length(c)
      println(s"  utf8Length('$c') = $l")
      l == expected
    }

    def checkEncodeDecode (c: Char): Boolean = {
      val bs = new Array[Byte](4)
      var i = 0
      var enc = UTFx.initUTF8Encoder(c)
      while (!enc.isEnd){
        bs(i) = enc.utf8Byte
        i += 1
        enc = enc.next(c)
      }
      val sIn = new String(Array(c))
      val sOut = new String(bs,0,i)
      println(s"  '$sIn' -> '$sOut'")
      sIn == sOut
    }

    val a: Array[Char] = Array('x', '\u00b6', '\u263b' )

    println(s"--- '${a(0)}'")
    assert( checkLength(a(0), 1))
    assert( checkEncodeDecode(a(0)))

    println(s"--- '${a(1)}'")
    assert( checkLength(a(1), 2))
    assert( checkEncodeDecode(a(1)))

    println(s"--- '${a(2)}'")
    assert( checkLength(a(2), 3))
    assert( checkEncodeDecode(a(2)))
  }

  "UTF8.Decoder" should "properly decode utf-8 Array[Byte] into Chars" in {
    ts.foreach { s =>
      val cs = s.toCharArray
      val bs = s.getBytes
      val css = cs.map( b=> (b.toInt & 0xffff).toHexString).mkString(",")
      val bss = bs.map( b=> (b.toInt & 0xff).toHexString).mkString(",")

      println(s"--- decode utf-16 for '$s'")
      println(s"       => utf-16: (${cs.length} : $css)")
      println(s"       => utf-8:  (${bs.length} : $bss)")
      var j = 0
      var decoder = UTFx.initUTF8Decoder(bs)
      while (!decoder.isEnd) {
        val c = decoder.utf16Char
        print(f"'$c' ($c%x) , state: ")
        decoder.print
        assert(c == cs(j))
        j += 1
        decoder = decoder.next(bs,bs.length)
      }
      j shouldBe cs.length
    }
  }

  "UTF8.utf16Length" should "compute the right UTF-16 length for UTF-8 Array[Byte]" in {
    ts.foreach { s=>
      val bs = s.getBytes

      val len = UTFx.utf16Length(bs)
      println(s"'$s' : utf-16 length= $len (expected ${s.length})")
      len shouldBe s.length
    }
  }

  "UTF8.equals" should "detect char equivalence with Strings" in {
    ts.foreach { s =>
      val bs = s.getBytes
      UTFx.utf8Equals(bs,s) shouldBe true
      bs(2) = '?'.toByte // make sure the 3rd byte is not part of a unicode char
      UTFx.utf8Equals(bs,s) shouldBe false
    }
  }
}
