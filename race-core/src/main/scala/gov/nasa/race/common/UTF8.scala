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

import scala.annotation.switch

/**
  * support functions for UTF-8 conversion without the need to use a CharSet/allocation
  */
object UTF8 {

  // used to keep track of encoding without allocating buffers
  // low 4 bytes are for encoded byte values
  // upper 4 encode char index (3 unsigned bytes) and number of remaining low bytes (1 byte)
  final class Encoder(val state: Long) extends AnyVal {

    @inline def isEnd: Boolean = state == 0L

    @inline def charIndex: Int = (state >> 48).toInt

    @inline def remainingBytes: Int = ((state & 0xff00000000L)>>32).toInt

    def utf8Byte: Byte = {
      (remainingBytes: @switch) match {
        case 1 => ((state & 0xffL).toByte)
        case 2 => ((state & 0xff00L) >> 8).toByte
        case 3 =>((state & 0xff0000L) >> 16).toByte
        case 4 =>((state & 0xff000000L) >> 24).toByte
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def next (cs: Array[Char], maxIndex: Int): Encoder = {
      (remainingBytes: @switch) match {
        case 1 => { // next char (if any)
          val i = charIndex+1
          if (i >= maxIndex) new Encoder(0) else initEncoder(cs,i)
        }

        case 2 => new Encoder( state & 0xffffff00ffffffffL | 0x100000000L)
        case 3 => new Encoder( state & 0xffffff00ffffffffL | 0x200000000L)
        case 4 => new Encoder( state & 0xffffff00ffffffffL | 0x300000000L)
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def print: Unit = {
      println(f"EncoderState( charIndex=$charIndex, remainingBytes=$remainingBytes, bytes=${state & 0xffffffffL}%x")
    }
  }

  def initEncoder (cs: Array[Char], off: Int): Encoder = {
    val idx = (off & 0xffffffL) << 48
    val c = cs(off)

    if (c < 0x80) {
      new Encoder( idx | 0x100000000L | (c & 0xffL))
    } else if (c < 0x800) {
      new Encoder( idx | 0x200000000L | (0xc0 | (c>>6))<<8 | (0x80 | (c & 0x3f)))
    } else if (c < 0xd800 || c >= 0xe000) {
      new Encoder( idx | 0x300000000L | (0xe0 | (c>>12))<<16 | (0x80 | (c>>6 & 0x3f))<<8 | (0x80 | (c & 0x3f)))
    } else { // surrogate pair
      throw new RuntimeException("not yet")
    }
  }

  def initEncoder (cs: Array[Char]): Encoder = initEncoder(cs,0)

  //--- utf8 length calculation

  def utf8Length (cs: Array[Char], off: Int, len: Int): Int = {
    var utf8Len: Int = 0
    val i1 = off+len
    var i = off

    while (i < i1){
      val c = cs(i)

      if (c < 0x80) utf8Len += 1
      else if (c < 0x800) utf8Len += 2
      else if (c < 0xd800 || c >= 0xe000) utf8Len += 3
      else utf8Len += 4

      i += 1
    }

    utf8Len
  }

  @inline def utf8Length(cs: Array[Char]): Int = utf8Length(cs,0,cs.length)

  def utf8Length (s: String): Int = {
    var utf8Len: Int = 0
    val len = s.length
    var i = 0

    while (i < len){
      val c = s.charAt(i)

      if (c < 0x80) utf8Len += 1
      else if (c < 0x800) utf8Len += 2
      else if (c < 0xd800 || c >= 0xe000) utf8Len += 3
      else utf8Len += 4

      i += 1
    }

    utf8Len
  }

  def isASCII (data: Array[Char], off: Int, len: Int): Boolean = {
    val i1 = off+len
    var i = off
    while (i < i1) {
      if ((data(i) & 0xff00) != 0) return false
      i += 1
    }
    true
  }

  def isACII (s: String): Boolean = {
    val len = s.length
    var i = 0
    while (i < len) {
      if ((s.charAt(i) & 0xff00) != 0) return false
      i += 1
    }
    true
  }
}
