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
package gov.nasa.race.common.inlined

import java.io.OutputStream

import gov.nasa.race.common.{ASCII8Internalizer, IntRange, Internalizer, UTF8Buffer}

import scala.annotation.switch

object Slice {

  @inline def apply (s: String): Slice = {
    val bs = s.getBytes()
    new Slice(bs,0,bs.length)
  }

  @inline def apply (bs: Array[Byte]): Slice = new Slice(bs,0,bs.length)

  @inline def apply (bs: Array[Byte], off: Int, len: Int): Slice = new Slice(bs,off,len)

  def hashed(s: String): Slice = {
    val bs = s.getBytes()
    val slice = new Slice(bs,0,bs.length)
    slice.hashCode
    slice
  }

  def empty: Slice = new Slice(Array.empty[Byte],0,0)

  final val EmptySlice = new Slice(null,0,0)

  final val TruePattern = "true".getBytes
  final val FalsePattern = "false".getBytes
  final val YesPattern = "yes".getBytes
  final val NoPattern = "no".getBytes
  final val NullPattern = "null".getBytes
  final val NaNPattern = "NaN".getBytes
}

/**
  * a sub-range into a byte array representing strings/chars
  *
  * the trait does not allow to modify the internals
  */
final class Slice (var data: Array[Byte], var offset: Int, var length: Int) {

  var hash: Int = 0

  @inline final def isEmpty: Boolean = length == 0
  @inline final def nonEmpty: Boolean = length > 0

  @inline final def limit: Int = offset + length

  override def toString: String = if (length > 0) new String(data,offset,length) else ""

  // same as String (utf-8)
  override def hashCode: Int = {
    var h = hash
    if (h == 0 && length > 0) {
      h = data(offset) & 0xff
      var i = offset+1
      val iEnd = offset + length
      while (i < iEnd) {
        h = h*31 + (data(i) & 0xff)
        i += 1
      }
      hash = h
    }
    h
  }

  @inline def apply (i: Int): Byte = data(offset+i)

  @inline def clear: Unit = {
    data = Array.empty[Byte]
    offset = 0
    length = 0
    hash = 0
  }

  @inline def clearRange: Unit = {
    offset = 0
    length = 0
    hash = 0
  }

  @inline def set (bsNew: Array[Byte], offNew: Int, lenNew: Int): Unit = {
    data = bsNew
    offset = offNew
    length = lenNew
    hash = 0
  }

  @inline def setRange (offNew: Int, lenNew: Int): Unit = {
    offset = offNew
    length = lenNew
    hash = 0
  }

  @inline def setFrom (other: Slice): Unit = set(other.data,other.offset,other.length)

  // note - this is not public since it does not compare lengths (which is the inlined part of the caller)
  private def equalBytes (otherBs: Array[Byte], otherOffset: Int): Boolean = {
    val bs = this.data
    var i = offset
    val iEnd = i + length
    var j = otherOffset
    while (i < iEnd) {
      if (bs(i) != otherBs(j)) return false
      i += 1
      j += 1
    }
    true
  }

  @inline def equalsSliceAt (other: Slice, start: Int): Boolean = {
    (length - start >= other.length) && other.equalBytes(data, offset+start)
  }


  @inline final def == (other: Slice): Boolean = {
    (length == other.length) && equalBytes(other.data,other.offset)
  }

  @inline final def != (other: Slice): Boolean = {
    (length != other.length) || !equalBytes(other.data,other.offset)
  }

  @inline def equals(otherBs: Array[Byte], otherOffset: Int, otherLength: Int): Boolean = {
    (length == otherLength) && equalBytes(otherBs,otherOffset)
  }

  @inline def equalsString (s: String): Boolean = {
    val sbs = s.getBytes // BAD - this is allocating
    (length == sbs.length) && equalBytes(sbs,0)
  }

  @inline def equalsString (s: String, buf: UTF8Buffer): Boolean = {
    val len = buf.encode(s)
    (length == len) && equalBytes(buf.data,0)
  }

  @inline def equalsBuffer (buf: UTF8Buffer): Boolean = {
    (length == buf.length) && equalBytes(buf.data,0)
  }

  override def equals (o: Any): Boolean = {
    o match {
      case other: Slice => this == other
      case s: String => equalsString(s)
      case _ => false
    }
  }


  // todo - add string comparison based on utf-8 encoding

  def equalsIgnoreCase(otherBs: Array[Byte], otherOffset: Int, otherLength: Int): Boolean = {
    if (length != otherLength) return false
    var i = offset
    val iEnd = i + length
    var j = otherOffset
    while (i < iEnd) {
      if ((data(i)|32) != (otherBs(j)|32)) return false
      i += 1
      j += 1
    }
    true
  }

  def equalsIgnoreCase (otherBs: Array[Byte]): Boolean = equalsIgnoreCase(otherBs,0,otherBs.length)

  @inline def intern: String = {
    if (length > 8) {
      Internalizer.get(data, offset, length)
    } else {
      ASCII8Internalizer.get(data,offset,length)
    }
  }

  def isNullValue: Boolean = {
    if (length == 4) {
      val i = offset
      val data = this.data
      (data(i) == 'n' && data(i+1) == 'u' && data(i+2) == 'l' && data(i+3) == 'l')
    } else false
  }

  def writeTo(out: OutputStream): Unit = {
    out.write(data,offset,length)
  }

  //--- type conversion

  def toSubString (off: Int = offset, len: Int = length): String = {
    val iMax = offset + length
    if (off >= offset && off < iMax && off+len <= iMax) new String(data,off,len) else ""
  }

  def toDouble: Double = {

    @inline def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'
    @inline def digitValue(b: Byte): Int = b - '0'

    var i = offset
    val iMax = i + length
    val bs = this.data
    var n: Long = 0
    var d: Double = 0.0
    var e: Long = 1
    var b: Byte = 0

    if (i >= iMax) throw new NumberFormatException(this.toString)
    val sig = if (bs(i)=='-') {i+= 1;  -1 } else 1

    //--- integer part
    while (i < iMax && {b=bs(i); isDigit(b)}){
      n = (n*10) + digitValue(b)
      i += 1
    }

    //--- fractional part
    if (b == '.') {
      i += 1
      var m: Long = 1
      var frac: Int = 0
      while (i < iMax && {b=bs(i); isDigit(b)}){
        frac = (frac*10) + digitValue(b)
        m *= 10
        i += 1
      }
      d = frac/m.toDouble
    }

    //--- exponent part
    if ((b|32) == 'e'){
      i += 1
      if (i >= iMax) throw new NumberFormatException(this.toString)

      e = if ({b=bs(i); b == '-'}){
        i += 1
        -1
      } else if (b == '+'){
        i += 1
        1
      } else 1
      var exp: Int = 0
      while (i < iMax && {b=bs(i); isDigit(b)}){
        exp = (exp*10) + digitValue(b)
        i += 1
      }

      var j = 0
      while (j < exp) { e *= 10; j += 1 }
    }

    if (i < iMax) throw new NumberFormatException(this.toString)

    if (e < 0){
      sig * -(n + d) / e
    } else {
      sig * (n + d) * e
    }
  }

  def trim: Slice = {
    var i = offset
    val iMax = offset + length
    val bs = this.data

    while (i < iMax && bs(i) == ' ') i += 1

    if (i == iMax) { // completely blank
      offset = 0
      length = 0

    } else {
      offset = i
      i = iMax-1
      while (i > offset && bs(i) == ' ') i -= 1
      length = i - offset + 1
    }

    this
  }

  def toHexLong: Long = {
    var i = offset
    val iMax = offset + length
    val bs = this.data
    var n: Long = 0

    while (i < iMax){
      n <<= 4
      n |= hexDigit(bs(i))
      i += 1
    }

    n
  }

  def toLong: Long = {

    @inline def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'
    @inline def digitValue(b: Byte): Int = b - '0'

    var i = offset
    val iMax = i + length
    val bs = this.data
    var n: Long = 0
    var b: Byte = 0

    if (i >= iMax) throw new NumberFormatException(this.toString)
    val sig = if (bs(i)=='-') {i+= 1;  -1 } else 1

    //--- integer part
    while (i < iMax && {b=bs(i); isDigit(b)}){
      n = (n*10) + digitValue(b)
      i += 1
    }

    if (i < iMax) throw new NumberFormatException(this.toString)

    sig * n
  }

  def toHexInt: Int = {
    var i = offset
    val iMax = offset + length
    val bs = this.data
    var n: Int = 0

    while (i < iMax){
      n <<= 4
      n |= hexDigit(bs(i))
      i += 1
    }

    n
  }

  def toInt: Int = {
    val l = toLong
    // todo - not the standard behavior, which silently trucates
    if (l > Int.MaxValue || l < Int.MinValue) throw new NumberFormatException(this.toString)
    l.toInt
  }

  def toByte: Byte = {
    val l = toLong
    if (l > Byte.MaxValue || l < Byte.MinValue) throw new NumberFormatException(this.toString)
    l.toByte
  }

  def toBoolean: Boolean = {
    // todo - more precise than Boolean.parseBoolean, but do we want to differ?
    if (length == 1){
      if (data(offset) == '1') true
      else if (data(offset) == '0') false
      else throw new RuntimeException(s"not a boolean: $this")
    } else {
      if (equalsIgnoreCase(Slice.TruePattern)) true
      else if (equalsIgnoreCase(Slice.FalsePattern)) false
      else if (equalsIgnoreCase(Slice.YesPattern)) true
      else if (equalsIgnoreCase(Slice.NoPattern)) false
      else  throw new RuntimeException(s"not a boolean: $this")
    }
  }

  /** less permissive version (only accepts "true" or "false" */
  def toTrueOrFalse: Boolean = {
    if (equalsIgnoreCase(Slice.TruePattern)) true
    else if (equalsIgnoreCase(Slice.FalsePattern)) false
    else  throw new RuntimeException(s"not a boolean: $this")
  }

  @inline def toDoubleOrNaN: Double = {
    if (length == 0 || equalsIgnoreCase(Slice.NullPattern) || equalsIgnoreCase(Slice.NaNPattern)) Double.NaN else toDouble
  }

  @inline final def hexDigit (b: Byte): Int = {
    (b: @switch) match {
      case '0' => 0
      case '1' => 1
      case '2' => 2
      case '3' => 3
      case '4' => 4
      case '5' => 5
      case '6' => 6
      case '7' => 7
      case '8' => 8
      case '9' => 9
      case 'A' | 'a' => 10
      case 'B' | 'b' => 11
      case 'C' | 'c' => 12
      case 'D' | 'd' => 13
      case 'E' | 'e' => 14
      case 'F' | 'f' => 15
      case _ => throw new NumberFormatException("not a hex number")
    }
  }

  def literalToString: String = toEscString(offset+1,length-2) // remove the leading/trailing double quotes and replace escape chars

  // turn into String with replaced \x chars
  def toEscString (i0: Int = offset, len: Int = length): String = {
    val data = this.data
    val bs = new Array[Byte](len) // can only get shorter
    var i = 0
    var j = i0
    val j1 = j + len
    while (j < j1) {
      val b = data(j)
      if (b == '\\') {
        if (j < j1-1) {
          j += 1
          data(j) match {
            case 'n' => bs(i) = '\n'; i += 1; j += 1
            case 'r' => bs(i) = '\r'; i += 1; j += 1
            case 't' => bs(i) = '\t'; i += 1; j += 1
            case 'b' => bs(i) = '\b'; i += 1; j += 1
            case 'f' => bs(i) = '\f'; i += 1; j += 1
            case '\\' => bs(i) = '\\'; i += 1; j += 1

            case 'u' => { // 4 digit unicode, not particularly efficient
              if (j < j1-4){
                var codePoint: Int = hexDigit(data(j+1)) << 24
                codePoint += hexDigit(data(j+2)) << 16
                codePoint += hexDigit(data(j+3)) << 8
                codePoint += hexDigit(data(j+4))
                val ub = Character.toString(codePoint).getBytes
                var k = 0
                while (k < ub.length) {
                  bs(i) = ub(k)
                  i += 1
                  k += 1
                }
                j += 5
              } else {
                bs(i) = '\\'
                bs(i+1) = 'u'
                i += 2
                j += 1
              }
            }

            case other =>
              bs(i) = '\\'
              bs(i+1) = other;
              i += 2; j += 1
          }
        }
      } else {
        bs(i) = b
        i += 1
        j += 1
      }
    }

    new String(bs,0,i)
  }

  @inline def getIntRange: IntRange = IntRange(offset,length)
}

final class ASCIICharSequence (bs: Array[Byte], off: Int, len: Int) extends CharSequence {

  def this (slice: Slice) = this(slice.data, slice.offset, slice.length)

  @inline def length(): Int = len

  @inline def charAt(i: Int): Char = (bs(i) & 0xff).toChar

  override def subSequence(start: Int, end: Int): CharSequence = {
    new ASCIICharSequence(bs, off+start, end-start)
  }

  override def toString: String = new String(bs,off,len)
}
