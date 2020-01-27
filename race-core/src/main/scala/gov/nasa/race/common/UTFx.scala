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
  * support functions for UTF-x conversion without the need to use a CharSet/allocation
  *
  * TODO - check for possible optimizations for conversions. This also does not handle modified utf-8
  */
object UTFx {

  // low 4 bytes are next char(s)
  // high 4 bytes are 3 byte utf-8 index and one byte remaining chars
  // NOTE - this means we can only decode strings up to a length of 16,777,215 bytes
  final class UTF8Decoder(val state: Long) extends AnyVal {

    @inline def isEnd: Boolean = state == 0xffffffff00000000L

    @inline def nextByteIndex: Int = (state >> 40).toInt

    @inline def remainingChars: Int = (state >> 32 & 0xffL).toInt

    def utf16Char: Char = {
      if ((state & 0x200000000L) == 0) {
        (state & 0xffffL).toChar
      } else {    // surrogate pair
        (state>>16 & 0xffffL).toChar
      }
    }

    def utf16Char1: Char = (state & 0xffffL).toChar

    def utf16Char2: Char = (((state>>16) & 0xffffL)).toChar

    def next (bs: Array[Byte], maxIndex: Int): UTF8Decoder = {
      if ((state & 0x200000000L) == 0) {
        val i = nextByteIndex
        if (i >= maxIndex) new UTF8Decoder(0xffffffff00000000L) else initUTF8Decoder(bs, i)
      } else {
        new UTF8Decoder((state ^ 0x300000000L ))
      }
    }

    // use this in a loop that guarantees we are not exceeding the max index
    @inline def ++ (bs: Array[Byte]): UTF8Decoder = {
      if ((state & 0x200000000L) == 0) {
        initUTF8Decoder(bs, nextByteIndex)
      } else {
        new UTF8Decoder((state ^ 0x300000000L ))
      }
    }

    def print: Unit = {
      println(f"Decoder( nextByteIndex=$nextByteIndex, remainingChars=$remainingChars, utf16Chars= ($utf16Char2%x,$utf16Char1%x))")
    }
  }

  def initUTF8Decoder(bs: Array[Byte], off: Int): UTF8Decoder = {
    var i = off
    val b0 = bs(i) & 0xff
    if ((b0 & 0x80) == 0) { // single byte char (ASCII)
      new UTF8Decoder((i+1).toLong << 40 | 0x100000000L | b0)

    } else {
      i += 1
      val b1 = bs(i) & 0xff

      if (b0 >> 5 == 0x6) { // 110b prefix => 2 bytes (1 char)
        new UTF8Decoder((i+1).toLong << 40 | 0x100000000L | (b0 & 0x1fL)<<6 | (b1 & 0x3fL))

      } else {
        i += 1
        val b2 = bs(i) & 0xff

        if (b0 >> 4 == 0xe) { // 1110b prefix => 3 bytes (1 char)
          new UTF8Decoder((i+1).toLong << 40 | 0x100000000L | (b0 & 0xfL)<<12 | (b1 & 0x3fL)<<6 | (b2 & 0x3fL))

        } else if (b0 >> 3 == 0x1e) { // 11110 prefix => surrogate pair: 4 bytes (2 chars)
          i += 1
          val b3 = bs(i) & 0xff
          // 11110yyy 10yyyyyy 	10yyxxxx 	10xxxxxx    => 110110yy.yyyyyy.xx  110111xxxx.xxxxxx
          val v = ((b0 & 0x3)<<18 | (b1 & 0x3f)<<12 | (b2 & 0x3f)<<6 | (b3 & 0x3f)) - 0x10000
          val c1 = 0xd800L | (v>>10)
          val c2 = 0xdc00L | (v & 0x3ff)

          new UTF8Decoder((i+1).toLong << 40 | 0x200000000L | (c1 << 16) | c2)

        } else throw new RuntimeException(f"invalid utf8 lead byte $b0%x")
      }
    }
  }
  @inline def initUTF8Decoder(bs: Array[Byte]): UTF8Decoder = initUTF8Decoder(bs,0)

  def utf16Length (bs: Array[Byte], off: Int, len: Int): Int = {
    var utf16Length = 0
    val i1 = off + len
    var i = off
    while (i < i1) {
      val c0 = bs(i) & 0xff

      if ((c0 & 0x80) == 0) i += 1
      else if ((c0 & 0xe0) == 0xc0) i += 2
      else if ((c0 & 0xf0) == 0xe0) i += 3
      else if ((c0 & 0xf8) == 0xf0) {
        utf16Length += 1
        i += 4
      }
      else throw new RuntimeException("invalid utf8 data")

      utf16Length += 1
    }
    utf16Length
  }
  @inline def utf16Length(bs: Array[Byte]): Int = utf16Length(bs,0,bs.length)

  // used to keep track of encoding without allocating buffers
  // low 4 bytes are for encoded byte values
  // upper 4 encode char index (3 unsigned bytes) and number of remaining low bytes (1 byte)
  final class UTF8Encoder(val state: Long) extends AnyVal {

    @inline def isEnd: Boolean = state == 0L

    @inline def charIndex: Int = ((state >> 40) & 0xffffffL).toInt

    @inline def remainingBytes: Int = ((state>>32) & 0xffL).toInt

    def utf8Byte: Byte = {
      (remainingBytes: @switch) match {
        case 1 => ((state & 0xffL).toByte)
        case 2 => ((state & 0xff00L) >> 8).toByte
        case 3 =>((state & 0xff0000L) >> 16).toByte
        case 4 =>((state & 0xff000000L) >> 24).toByte
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def next (c: Char): UTF8Encoder = {
      (remainingBytes: @switch) match {
        case 1 => new UTF8Encoder(0) // there is no next char
        case 2 => new UTF8Encoder(state ^ 0x300000000L)
        case 3 => new UTF8Encoder(state ^ 0x100000000L)
        case 4 => new UTF8Encoder(state ^ 0x700000000L)
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def next (cs: Array[Char], maxIndex: Int): UTF8Encoder = {
      (remainingBytes: @switch) match {
        case 1 => { // next char (if any)
          val i = charIndex+1
          if (i >= maxIndex) new UTF8Encoder(0) else initUTF8Encoder(cs,i)
        }

        case 2 => new UTF8Encoder( state ^ 0x300000000L)
        case 3 => new UTF8Encoder( state ^ 0x100000000L)
        case 4 => new UTF8Encoder( state ^ 0x700000000L)
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def next (s: String): UTF8Encoder = {
      (remainingBytes: @switch) match {
        case 1 => { // next char (if any)
          val i = charIndex+1
          if (i >= s.length) new UTF8Encoder(0) else initUTF8Encoder(s,i)
        }

        case 2 => new UTF8Encoder( state ^ 0x300000000L)
        case 3 => new UTF8Encoder( state ^ 0x100000000L)
        case 4 => new UTF8Encoder( state ^ 0x700000000L)
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    // use this in a loop that guarantees we do not exceed the max index
    def ++ (cs: Array[Char]): UTF8Encoder = {
      (remainingBytes: @switch) match {
        case 1 => initUTF8Encoder(cs,charIndex+1)
        case 2 => new UTF8Encoder( state ^ 0x300000000L)
        case 3 => new UTF8Encoder( state ^ 0x100000000L)
        case 4 => new UTF8Encoder( state ^ 0x700000000L)
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def ++ (s: String): UTF8Encoder = {
      (remainingBytes: @switch) match {
        case 1 => initUTF8Encoder(s,charIndex+1)
        case 2 => new UTF8Encoder( state ^ 0x300000000L)
        case 3 => new UTF8Encoder( state ^ 0x100000000L)
        case 4 => new UTF8Encoder( state ^ 0x700000000L)
        case _ => throw new RuntimeException("invalid utf8 encoding state")
      }
    }

    def print: Unit = {
      println(f"Encoder( charIndex=$charIndex, remainingBytes=$remainingBytes, bytes=${state & 0xffffffffL}%x")
    }
  }

  def initUTF8Encoder (c: Char): UTF8Encoder = {
    if (c < 0x80) {
      new UTF8Encoder( 0x100000000L | (c & 0xffL))
    } else if (c < 0x800) {
      new UTF8Encoder( 0x200000000L | (0xc0 | (c>>6))<<8 | (0x80 | (c & 0x3f)))
    } else if (c < 0xd800 || c >= 0xe000) {
      new UTF8Encoder( 0x300000000L | (0xe0 | (c>>12))<<16 | (0x80 | (c>>6 & 0x3f))<<8 | (0x80 | (c & 0x3f)))
    } else {
      throw new RuntimeException("can't encode single char of surrogate pair") // FIXME - pass through
    }
  }

  def initUTF8Encoder (highSurrogate: Char, lowSurrogate: Char): UTF8Encoder = {
    val v = ((highSurrogate & 0xffff) - 0xd800) << 10 | ((lowSurrogate & 0xffff) - 0xdc00) + 0x10000

    new UTF8Encoder( (1<<40) | 0x400000000L |
      ((0xf0L | (v>>18)) <<24) | ((0x80L | ((v>>12) & 0x3f)) <<16) | ((0x80L | ((v>>6) & 0x3f)) <<8) | (0x80L | (v& 0x3f))
    )
  }

  def initUTF8Encoder(cs: Array[Char], off: Int): UTF8Encoder = {
    if (off >= cs.length) return new UTF8Encoder(0L)

    var idx: Int = off
    val c: Int = cs(idx) & 0xffff

    if (c < 0x80) {
      new UTF8Encoder( ((idx.toLong)<<40) | 0x100000000L | (c & 0xffL))
    } else if (c < 0x800) {
      new UTF8Encoder( ((idx.toLong)<<40) | 0x200000000L | (0xc0 | (c>>6))<<8 | (0x80 | (c & 0x3f)))
    } else if (c < 0xd800 || c >= 0xe000) {
      new UTF8Encoder( ((idx.toLong)<<40) | 0x300000000L | (0xe0 | (c>>12))<<16 | (0x80 | (c>>6 & 0x3f))<<8 | (0x80 | (c & 0x3f)))
    } else { // surrogate pair
      idx += 1
      val v = ((c & 0xffff) - 0xd800) << 10 | ((cs(idx) & 0xffff) - 0xdc00) + 0x10000

      new UTF8Encoder( ((idx.toLong)<<40) | 0x400000000L |
        ((0xf0L | (v>>18)) <<24) | ((0x80L | ((v>>12) & 0x3f)) <<16) | ((0x80L | ((v>>6) & 0x3f)) <<8) | (0x80L | (v& 0x3f))
      )
    }
  }

  @inline def initUTF8Encoder(cs: Array[Char]): UTF8Encoder = initUTF8Encoder(cs,0)

  @inline def initUTF8Encoder(s: String, off: Int): UTF8Encoder = {
    if (off >= s.length) return new UTF8Encoder(0L)

    var idx: Int = off
    val c: Int = s.charAt(idx) & 0xffff

    if (c < 0x80) {
      new UTF8Encoder( ((idx.toLong)<<40) | 0x100000000L | (c & 0xffL))
    } else if (c < 0x800) {
      new UTF8Encoder( ((idx.toLong)<<40) | 0x200000000L | (0xc0 | (c>>6))<<8 | (0x80 | (c & 0x3f)))
    } else if (c < 0xd800 || c >= 0xe000) {
      new UTF8Encoder( ((idx.toLong)<<40) | 0x300000000L | (0xe0 | (c>>12))<<16 | (0x80 | (c>>6 & 0x3f))<<8 | (0x80 | (c & 0x3f)))
    } else { // surrogate pair
      idx += 1
      val v = ((c & 0xffff) - 0xd800) << 10 | ((s.charAt(idx) & 0xffff) - 0xdc00) + 0x10000

      new UTF8Encoder( ((idx.toLong)<<40) | 0x400000000L |
        ((0xf0L | (v>>18)) <<24) | ((0x80L | ((v>>12) & 0x3f)) <<16) | ((0x80L | ((v>>6) & 0x3f)) <<8) | (0x80L | (v& 0x3f))
      )
    }
  }

  //--- utf8 length calculation

  @inline def isSurrogatePairChar (c: Char): Boolean = {
    c >= 0xd800 && c < 0xe000
  }

  // an alternative to Java 11 Character.toString(Int)
  def codePointToUtf8 (cp: Int, bs: Array[Byte], off: Int=0): Int = {
    if (cp >= 0xffff) {
      bs(off) = (0xf0 | ((cp >>18) & 0x07)).toByte
      bs(off+1) = (0x80 | ((cp >> 12) & 0x3f)).toByte
      bs(off+2) = (0x80 | ((cp >> 6) & 0x3f)).toByte
      bs(off+3) = (0x80 | (cp & 0x3f)).toByte
      4
    } else if (cp >= 0x0800) {
      bs(off) = (0xe0 | ((cp >> 12) & 0x0f)).toByte
      bs(off+1) = (0x80 | ((cp >> 6) & 0x3f)).toByte
      bs(off+2) = (0x80 | (cp & 0x3f)).toByte
      3
    } else if (cp  >= 0x0080) {
      bs(off) = (0xc0 | (cp >> 6) & 0x1f). toByte
      bs(off+1) = (0x80 | (cp & 0x3f)).toByte
      2
    }else {
      bs(off) = (cp & 0x7f).toByte
      1
    }
  }

  def utf8Length (c: Char): Int = {
    if (c < 0x80) 1
    else if (c < 0x800) 2
    else if (c < 0xd800 || c >= 0xe000) 3
    else { // TODO - what shall we do with single surrogate char?
      2
    }
  }

  def utf8Length (cs: Array[Char], off: Int, len: Int): Int = {
    var utf8Len: Int = 0
    val i1 = off+len
    var i = off

    while (i < i1){
      val c = cs(i)

      if (c < 0x80) utf8Len += 1
      else if (c < 0x800) utf8Len += 2
      else if (c < 0xd800 || c >= 0xe000) utf8Len += 3
      else { // surrogate pair (we check each char)
        utf8Len += 2
      }

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
      else { // surrogate pair (we check each char
        utf8Len += 2
      }

      i += 1
    }

    utf8Len
  }

  def isASCII (data: Array[Byte], off: Int, len: Int): Boolean = {
    val i1 = off+len
    var i = off
    while (i < i1) {
      if ((data(i) & 0x80) != 0) return false
      i += 1
    }
    true
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

  def utf8Equals (bs: Array[Byte], off: Int, len: Int, s: String): Boolean = {
    val end = off+len
    var dec = initUTF8Decoder(bs,off)
    var j = 0
    while (!dec.isEnd){
      if (dec.utf16Char != s.charAt(j)) return false
      j += 1
      dec = dec.next(bs,end)
    }
    (j == s.length)
  }

  @inline def utf8Equals (bs: Array[Byte], s: String): Boolean = utf8Equals(bs, 0, bs.length, s)

  def utf16Equals (cs: Array[Char], off: Int, len: Int, s: String): Boolean = {
    var i = 0
    var j = off
    while (i < len) {
      if (cs(j) != s.charAt(i)) return false
      j += 1
      i += 1
    }
    true
  }

  def asciiEquals (bs: Array[Byte], off: Int, len: Int, s: String): Boolean = {
    var i1 = off+len
    var i = 0
    while (i < i1) {
      if ((bs(i) & 0xff) != s.charAt(i)) return false
      i += 1
    }
    (i == s.length)
  }
}
