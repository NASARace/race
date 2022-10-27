/*
 * Copyright (c) 2020, United States Government, as represented by the
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

import java.nio.file.Path
import java.util.function.IntConsumer
import java.util.stream.{IntStream, StreamSupport}
import java.util.{NoSuchElementException, PrimitiveIterator, Spliterator, Spliterators}

import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.DateTimeUtils

import scala.annotation.switch
import scala.concurrent.duration.Duration
import scala.language.implicitConversions


object ConstCharSequences {
  final val True = ConstAsciiSlice("true")
  final val False = ConstAsciiSlice("false")
  final val Yes = ConstAsciiSlice("yes")
  final val No = ConstAsciiSlice("no")
  final val Null = ConstAsciiSlice("null")
  final val NaN = ConstAsciiSlice("NaN")
}
import gov.nasa.race.common.ConstCharSequences._

/**
  * a byte slice that encodes Chars
 *
 * NOTE - CharSeqByteSlices cannot be used as Map key types when using String key values - equals() is not symmetric
 * (String equals checks for exact type). Key values have to be of the exact same type
  */
trait CharSeqByteSlice extends ByteSlice with CharSequence {

  override def isEmpty: Boolean = len == 0 // make sure we don't pick up the CharSequence default method, which would cause a compile error

  // the char accessors
  def length: Int // in chars!
  def charAt (i: Int): Char
  def subSequence(start: Int, end: Int): CharSequence

  // non-allocating chars comparison
  def =:=(s: CharSequence): Boolean

  def contains (c: Char): Boolean

  // same as String (utf-8)
  override def hashCode: Int = {
    var h = 0
    if (len > 0) {
      h = data(off) & 0xff
      var i = off+1
      val iEnd = off + len
      while (i < iEnd) {
        h = h*31 + (data(i) & 0xff)
        i += 1
      }
    }
    h
  }

  // NOTE - this does not compare equal to String instances (which does not support CharSequence value comparison)
  override def equals (o: Any): Boolean = {
    o match {
        // unfortunately we can't widen this to general CharSequence since it wouldn't be symmetric: String.equals(CSBS) != CSBS.equals(String)
      case csbs: CharSeqByteSlice => this =:= csbs
      case _ => false
    }
  }

  def equalsCharSequence (cs: CharSequence): Boolean = {
    if (length == cs.length()) {
      var i = 0
      while (i<len) {
        if (charAt(i) != cs.charAt(i)) return false
        i += 1
      }
      true
    } else false
  }

  @inline def equalsString (s: String): Boolean = {
    val sbs = s.getBytes // BAD - this is allocating
    (len == sbs.length) && equalsData(sbs,0)
  }

  @inline def equalsString (s: String, buf: Utf8Buffer): Boolean = {
    val l = buf.encode(s)
    (len == l) && equalsData(buf.data,0)
  }

  /**
    * FIXME - this is not correct for general utf8, only for ASCII
    */
  def equalsIgnoreCase (other: CharSeqByteSlice): Boolean = {
    if (len != other.len) return false
    val bs = this.data
    val otherBs = other.data

    var i = off
    val iEnd = i + len
    var j = other.off
    while (i < iEnd) {
      if ((bs(i)|32) != (otherBs(j)|32)) return false
      i += 1
      j += 1
    }
    true
  }

  def isNullValue: Boolean = equalsIgnoreCase(Null)
  def isNaNValue: Boolean = equalsIgnoreCase(NaN)

  // basic type converters

  def intern: String = Internalizer.get(data, off, len)

  @inline final override def toString: String = new String(data,off,len)

  def toBoolean: Boolean = {
    // todo - more precise than Boolean.parseBoolean, but do we want to differ?
    if (len == 1){
      if (data(off) == '1') true
      else if (data(off) == '0') false
      else throw new RuntimeException(s"not a boolean: $this")
    } else {
      if (equalsIgnoreCase(True)) true
      else if (equalsIgnoreCase(False)) false
      else if (equalsIgnoreCase(Yes)) true
      else if (equalsIgnoreCase(No)) false
      else  throw new RuntimeException(s"not a boolean: $this")
    }
  }

  /** less permissive version (only accepts "true" or "false" */
  @inline final def toTrueOrFalse: Boolean = {
    if (equalsIgnoreCase(True)) true
    else if (equalsIgnoreCase(False)) false
    else  throw new RuntimeException(s"not a boolean: $this")
  }

  @inline final def toDoubleOrNaN: Double = {
    if (len == 0 || equalsIgnoreCase(Null) || equalsIgnoreCase(NaN)) Double.NaN else toDouble
  }

  @inline private def hexDigit (b: Byte): Int = {
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
      case _ => throw new NumberFormatException(s"not a hex number: '$this'")
    }
  }

  def toHexLong: Long = {
    var i = off
    val iMax = off + len
    val bs = this.data
    var n: Long = 0

    while (i < iMax){
      n <<= 4
      n |= hexDigit(bs(i))
      i += 1
    }

    n
  }

  @inline final def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'
  @inline final def digitValue(b: Byte): Int = b - '0'

  def toLong: Long = {
    var i = off
    val iMax = i + len
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
    var i = off
    val iMax = off + len
    val bs = this.data
    var n: Int = 0

    while (i < iMax){
      n <<= 4
      n |= hexDigit(bs(i))
      i += 1
    }

    n
  }

  def isInteger: Boolean = {
    var i = off
    val iMax = i + len
    val bs = this.data

    if (bs(i) == '-' || bs(i) == '+') i += 1 // skip over optional sign

    while (i < iMax) {
      val b = bs(i)
      if (b < '0' || b > '9') return false
      i += 1
    }

    true
  }

  /**
    * return if this is a (potentially signed) number and it has exactly one '.'
    */
  def isRationalNumber: Boolean = {
    var i = off
    val iMax = i + len
    val bs = this.data
    var isRational = false

    if (bs(i) == '-' || bs(i) == '+') i += 1 // skip over optional sign

    while (i < iMax) {
      val b = bs(i)
      if (b == '.') {
        if (isRational) return false // two '.'
        isRational = true
      } else {
        if (!isDigit(b)) return false;
      }
      i += 1
    }

    isRational
  }

  def toInt: Int = {
    val l = toLong
    // todo - not the standard behavior, which silently truncates
    if (l > Int.MaxValue || l < Int.MinValue) throw new NumberFormatException(this.toString)
    l.toInt
  }

  def toByte: Byte = {
    val l = toLong
    if (l > Byte.MaxValue || l < Byte.MinValue) throw new NumberFormatException(this.toString)
    l.toByte
  }

  def toDouble: Double = {

    @inline def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'
    @inline def digitValue(b: Byte): Int = b - '0'

    var i = off
    val iMax = i + len
    val data = this.data
    var n: Long = 0
    var d: Double = 0.0
    var e: Long = 1
    var b: Byte = 0

    if (i >= iMax) throw new NumberFormatException(this.toString)
    val sig = if (data(i)=='-') {i+= 1;  -1 } else 1

    //--- integer part
    while (i < iMax && {b=data(i); isDigit(b)}){
      n = (n*10) + digitValue(b)
      i += 1
    }

    //--- fractional part
    if (b == '.') {
      i += 1
      val i0 = i
      var frac: Long = 0
      while (i < iMax && {b=data(i); isDigit(b)}){
        frac = (frac*10) + digitValue(b)
        i += 1
      }
      d = frac/Math.pow(10, (i-i0).toDouble)
    }

    //--- exponent part
    if ((b|32) == 'e'){
      i += 1
      if (i >= iMax) throw new NumberFormatException(this.toString)

      e = if ({b=data(i); b == '-'}){
        i += 1
        -1
      } else if (b == '+'){
        i += 1
        1
      } else 1
      var exp: Int = 0
      while (i < iMax && {b=data(i); isDigit(b)}){
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

  def toOptionalDouble: Double = {
    if (isEmpty) Double.NaN else toDouble
  }

  // turn into String with replaced \x chars
  def toEscString (i0: Int = off, len: Int = len): String = {
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

            case 'u' => {
              def parseUtf16: Int = {
                (hexDigit(data(j+1)) << 24) +
                  (hexDigit(data(j+2)) << 16) +
                  (hexDigit(data(j+3)) << 8) +
                  hexDigit(data(j+4))
              }
              if (j < j1-4){
                var cp = parseUtf16
                j += 5
                if (cp >= 0xd800 && cp <= 0xdbff) { // high surrogate char - we need the low surrogate
                  if (data(j) != '\\' || data(j+1) != 'u') throw new RuntimeException("illegal surrogate pair")
                  j += 2
                  val low = parseUtf16
                  j += 5
                  cp = ((cp - 0xd800) << 10) + (low - 0xdc00) + 0x10000
                }
                i += UTFx.codePointToUtf8(cp,bs,i)

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

  // trim leading and trailing double quotes and replace escape sequences
  def literalToString: String = toEscString(off+1,len-2)

  def toDuration: Duration = {
    DateTimeUtils.parseDuration(this)
  }

  // NOTE - this guesses based on length if epoch is given in seconds or millis. Use explicit DateTime ctors if that is unsafe
  def toDateTime: DateTime = {
    if (isInteger) {
      if (len <= 10) DateTime.ofEpochSeconds(toLong) else DateTime.ofEpochMillis(toLong)
    } else {
      DateTime.parseISO(this)
    }
  }

  def toPath: Path = Path.of(toString)

  // to be provided by concrete type
  def createSubSequence (subOff: Int, subLen: Int): CharSeqByteSlice
}

trait MutCharSeqByteSlice extends CharSeqByteSlice with MutByteSlice

/**
  * a CharSequenceByteSlice that uses utf-8 byte encoding
  */
trait Utf8Slice extends CharSeqByteSlice {

  protected var nci = Int.MaxValue // next char index
  protected var decoder = new UTFx.UTF8Decoder(0)
  protected var charLength = -1  // computed on demand

  @inline def constCopy: ConstUtf8Slice = new ConstUtf8Slice(data,off,len)

  @inline private def checkBounds (i: Int): Boolean = {
    if (decoder.isEnd) throw new IndexOutOfBoundsException(s"char index $i out of bounds")
    true
  }

  def toCharArray: Array[Char] = {
    val l  = length
    val a = new Array[Char](l)
    reset()
    var i = 0
    while (i < l) {
      a(i) = charAt(i)
      i += 1
    }
    a
  }

  def reset(): Unit = {
    nci = 0
    decoder = UTFx.initUTF8Decoder(data,off)
  }

  // char length
  override def length: Int = {
    if (charLength < 0) charLength = UTFx.utf16Length(data,off,len)
    charLength
  }

  override final def charAt(ci: Int): Char = {
    val data = this.data
    val limit = this.limit
    if (ci < 0) throw new IndexOutOfBoundsException(s"negative char index $ci out of bounds")
    if (ci < nci) reset() // backstep - we have to start over from the beginning

    while (nci < ci){
      decoder = decoder.next(data,limit)
      checkBounds(ci)
      nci += 1
    }
    decoder.utf16Char
  }

  override def contains (c: Char): Boolean = {
    var i = 0
    val n = length
    reset()
    while (i<n){
      if (decoder.utf16Char == c) return true
      decoder = decoder.next(data,limit)
      i += 1
    }
    false
  }

  override def =:=(s: CharSequence): Boolean = {
    val n = s.length
    var i = 0
    if (n == length) {
      reset()
      while (i < n) {
        if (decoder.utf16Char != s.charAt(i)) return false
        decoder = decoder.next(data,limit)
        i += 1
      }
      true
    } else false
  }

  // NOTE start and end are char indices
  override def subSequence(start: Int, end: Int): CharSequence = {
    if (start < 0) throw new IndexOutOfBoundsException(s"negative start char index $start out of bounds")
    if (end < start) throw new IndexOutOfBoundsException(s"end char index $end lower than start index $start")

    reset()
    val iMax = off+len
    var biStart = off
    var biEnd = 0

    while (nci < start){
      checkBounds(start)
      biStart = decoder.nextByteIndex
      decoder = decoder.next(data,iMax)
      nci += 1
    }

    while (nci < end) {
      checkBounds(end)
      biEnd = decoder.nextByteIndex
      decoder = decoder.next(data,iMax)
      nci += 1
    }

    reset()
    createSubSequence(biStart, biEnd-biStart)
  }
}

object ConstUtf8Slice {
  final val EmptySlice = new ConstUtf8Slice(null,0,0)

  def apply (s: String) = {
    val bs = s.getBytes
    new ConstUtf8Slice(bs,0,bs.length)
  }
  def apply (bs: Array[Byte]) = new ConstUtf8Slice(bs,0,bs.length)
  def apply (bs: Array[Byte], off: Int, len: Int) = new ConstUtf8Slice(bs,off,len)
  def apply (cs: CharSeqByteSlice) = new ConstUtf8Slice(cs.data,cs.off,cs.len)

  @inline def utf8 (s: String): ConstUtf8Slice = apply(s)

  implicit def stringToConstUtf8Slice (s: String): ConstUtf8Slice = apply(s)

  implicit class ConstUtf8Interpolator (sc: StringContext) {
    def utf8 (subs: Any*): ConstUtf8Slice = {
      val pit = sc.parts.iterator
      val sit = subs.iterator
      val sb = new java.lang.StringBuilder(pit.next())
      while(sit.hasNext) {
        sb.append(sit.next().toString)
        sb.append(pit.next())
      }
      val bytes = sb.toString.getBytes
      new ConstUtf8Slice(bytes,0,bytes.length)
    }
  }
}

/**
  * a utf-8 encoded CharSequenceByteSlice whose fields are constant
  * NOTE - this does not guarantee to underlying bytes don't change
  */
class ConstUtf8Slice (val data: Array[Byte], val off: Int, val len: Int) extends Utf8Slice {
  override def createSubSequence(subOff: Int, subLen: Int): ConstUtf8Slice = {
    new ConstUtf8Slice(data, subOff, subLen)
  }
}

object MutUtf8Slice {
  @inline final def empty = new MutUtf8Slice(null,0,0)
}

/**
  * a utf-8 encoded CharSequenceByteSlice whose fields can be modified
  */
class MutUtf8Slice (var data: Array[Byte], var off: Int, var len: Int) extends Utf8Slice
                                                              with MutCharSeqByteSlice {

  override def set (newData: Array[Byte], newOff: Int, newLen: Int): Unit = {
    data = newData
    off = newOff
    len = newLen

    nci = 0
    decoder = UTFx.initUTF8Decoder(data,off)
    charLength = -1
  }

  override def createSubSequence(subOff: Int, subLen: Int): MutUtf8Slice = {
    new MutUtf8Slice(data, subOff, subLen)
  }

  def trimSelf: Unit = super.trimSelf(' ')

  override def clear(): Unit = {
    super.clear()
    charLength = 0
  }

  override def clearRange(): Unit = {
    super.clearRange()
    charLength = 0
  }
}


/**
  * a CharSequenceByteSlice that only holds ASCII chars and hence does not need byte encoding
  */
trait AsciiSlice extends CharSeqByteSlice {

  final class ASCIICharIterator(private[this] val data: Array[Byte],
                                private[this] var idx: Int,
                                private[this] val maxIdx: Int) extends PrimitiveIterator.OfInt {

    override def hasNext: Boolean = idx < maxIdx

    override def nextInt: Int = {
      if (idx < maxIdx) {
        val c = data(idx) & 0xff
        idx += 1
        c
      } else throw new NoSuchElementException
    }

    override def forEachRemaining(block: IntConsumer): Unit = {
      while (idx < maxIdx){
        block.accept(data(idx) & 0xff)
        idx += 1
      }
    }
  }

  @inline override def length: Int = len

  @inline final def charAt(i: Int): Char = (data(i+off) & 0xff).toChar

  def toCharArray: Array[Char] = {
    val a = new Array[Char](len)
    var i = 0
    while (i < len) {
      a(i) = charAt(i)
      i += 1
    }
    a
  }

  override def contains (c: Char): Boolean = {
    val n = len
    var i = 0
    while (i<n){
      if (charAt(i) == c) return true
      i += 1
    }
    false
  }

  override def =:=(s: CharSequence): Boolean = {
    val n = s.length
    var i=0
    if (len == n) {
      while (i<n){
        if (s.charAt(i) != charAt(i)) return false
        i += 1
      }
      true
    } else false
  }

  override def chars: IntStream = {
    StreamSupport.intStream(
      () => Spliterators.spliterator(new ASCIICharIterator(data,off,off+len), len, Spliterator.ORDERED),
      Spliterator.SUBSIZED | Spliterator.SIZED | Spliterator.ORDERED,
      false)
  }

  override def codePoints: IntStream = chars

  override def subSequence(start: Int, end: Int): CharSequence = {
    if (start >= len || end > len) throw new IndexOutOfBoundsException(s"subSequence $start..$end out of bounds 0..${len}")
    createSubSequence(off+start, end-start)
  }

  override def intern: String = {
    if (len > 8) {
      Internalizer.get(data, off, len)
    } else {
      ASCII8Internalizer.get(data,off,len)
    }
  }
}

object ConstAsciiSlice {
  @inline def apply (bs: Array[Byte]) = new ConstAsciiSlice(bs,0,bs.length)

  @inline def apply (s: String): ConstAsciiSlice = {
    val data = s.getBytes
    new ConstAsciiSlice(data,0,data.length)
  }

  @inline def asc (s:String): ConstAsciiSlice = apply(s)

  implicit def stringToConstAsciiSlice(s: String): ConstAsciiSlice = apply(s)

  implicit class ConstAsciiInterpolator (sc: StringContext) {
    def asc (subs: Any*): ConstAsciiSlice = {
      val pit = sc.parts.iterator
      val sit = subs.iterator
      val sb = new java.lang.StringBuilder(pit.next())
      while(sit.hasNext) {
        sb.append(sit.next().toString)
        sb.append(pit.next())
      }
      val bytes = sb.toString.getBytes
      new ConstAsciiSlice(bytes,0,bytes.length)
    }
  }
}

/**
  * a AsciiCharSequence with constant fields
  */
class ConstAsciiSlice (val data: Array[Byte], val off: Int, val len: Int) extends AsciiSlice {

  override def createSubSequence(subOff: Int, subLen: Int): ConstAsciiSlice = {
    new ConstAsciiSlice(data, subOff, subLen)
  }
}

object MutAsciiSlice {
  @inline final def empty = new MutAsciiSlice(null,0,0)

  @inline final def apply (s: String): MutAsciiSlice = {
    val bs = s.getBytes
    new MutAsciiSlice(bs,0,bs.length)
  }
}

/**
  * a AsciiCharSequence whose fields can be set externally
  */
class MutAsciiSlice (var data: Array[Byte], var off: Int, var len: Int) extends AsciiSlice
                                                                 with MutCharSeqByteSlice {
  override def createSubSequence(subOff: Int, subLen: Int): MutAsciiSlice = {
    new MutAsciiSlice(data, subOff, subLen)
  }

  def trimSelf: Unit = super.trimSelf(' ')
}
