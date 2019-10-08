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

import gov.nasa.race.common.{ASCII8Internalizer, Internalizer, UTF8Buffer}

object Slice {

  def apply (s: String): Slice = {
    val bs = s.getBytes()
    new Slice(bs,0,bs.length)
  }

  def apply (bs: Array[Byte], off: Int, len: Int): Slice = new Slice(bs,off,len)

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
}

/**
  * a sub-range into a byte array representing strings/chars
  *
  * the trait does not allow to modify the internals
  */
final class Slice (var data: Array[Byte], var offset: Int, var length: Int) {

  var hash: Int = 0

  final def isEmpty: Boolean = length == 0
  final def nonEmpty: Boolean = length > 0

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

  def writeTo(out: OutputStream): Unit = {
    out.write(data,offset,length)
  }

  //--- type conversion

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

  def toInt: Int = {
    val l = toLong
    if (l > Int.MaxValue || l < Int.MinValue) throw new NumberFormatException(this.toString) // todo - not the standard behavior, which silently trucates
    l.toInt
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
}

