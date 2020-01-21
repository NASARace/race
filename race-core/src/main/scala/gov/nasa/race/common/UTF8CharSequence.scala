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

import java.util.{NoSuchElementException, PrimitiveIterator, Spliterator, Spliterators}
import java.util.function.IntConsumer
import java.util.stream.{IntStream, StreamSupport}

import gov.nasa.race.common.inlined.Slice

/**
  * a wrapper for utf-8 data that provides a CharSequence interface
  *
  * NOTE - the provided data buffer has to start on utf-8 char boundaries
  *
  * although we make best efforts to cache the last char boundary this has much higher runtime
  * costs than ASCIICharSequence
  *
  * TODO - not clear if private[this] var fields really pay off performance-wise (they locally
  * use getfield insns instead of invokevirtual). They are ugly and error prone
  */
sealed abstract class UTF8CharSequence extends CharSequence with ByteRange {

  // those are provided/set by derived classes but are considered invariant here
  private[this] var _data: Array[Byte] = null
  private[this] var _offset: Int = 0
  private[this] var _byteLength: Int = 0

  private[this] var nci = -1 // next char index
  private[this] var decoder = UTFx.initUTF8Decoder(Array[Byte](0))
  private[this] var iMax = -1
  private[this] var charLength = -1  // computed on demand

  //--- ByteRange interface
  def data: Array[Byte] = _data
  def offset: Int = _offset
  def byteLength: Int = _byteLength

  protected def _init (bs: Array[Byte], off: Int, len: Int): Unit = {
    _data = bs
    _offset = off
    _byteLength = len

    nci = 0
    decoder = UTFx.initUTF8Decoder(bs,off)
    iMax = off+len
    charLength = -1
  }

  protected def _reset: Unit = {
    nci = 0
    decoder = UTFx.initUTF8Decoder(_data,_offset)
  }

  private def checkBounds (i: Int): Boolean = {
    if (decoder.isEnd) throw new IndexOutOfBoundsException(s"char index $i out of bounds")
    true
  }

  // char length
  override def length: Int = {
    if (charLength < 0) charLength = UTFx.utf16Length(_data,_offset,byteLength)
    charLength
  }

  final def charAt(ci: Int): Char = {
    if (ci < 0) throw new IndexOutOfBoundsException(s"negative char index $ci out of bounds")
    if (ci < nci) _reset // backstep - we have to start over from the beginning

    while (nci < ci){
      decoder = decoder.next(_data,iMax)
      checkBounds(ci)
      nci += 1
    }
    decoder.utf16Char
  }

  // start and end a char indices
  override def subSequence(start: Int, end: Int): CharSequence = {
    if (start < 0) throw new IndexOutOfBoundsException(s"negative start char index $start out of bounds")
    if (end < start) throw new IndexOutOfBoundsException(s"end char index $end lower than start index $start")

    _reset
    var biStart = 0
    while (nci < start){
      checkBounds(start)
      biStart = decoder.nextByteIndex
      decoder = decoder.next(_data,iMax)
      nci += 1
    }

    while (nci < end) {
      checkBounds(end)
      decoder = decoder.next(_data,iMax)
      nci += 1
    }
    val biEnd = decoder.nextByteIndex

    _reset
    new ConstUTF8CharSequence(_data,biStart, biEnd-biStart)
  }

  override def toString: String = new String(_data,_offset,byteLength)

  def toSlice: Slice = new Slice(_data,_offset,byteLength)
}

/**
  * a UTF8CharSequence that cannot be reused with changed data
  */
class ConstUTF8CharSequence (bs: Array[Byte], off:Int, len: Int) extends UTF8CharSequence {
  _init(bs,off,len)

  def this (bs: Array[Byte]) = this(bs,0,bs.length)
  def this (slice: Slice) = this(slice.data, slice.offset, slice.byteLength)
}

/**
  * a re-usable UTF8CharSequence that allows to set new data
  */
class MutUTF8CharSequence extends UTF8CharSequence {
  def initialize (bs: Array[Byte]): Unit = _init(bs,0,bs.length)
  def initialize (bs: Array[Byte], off: Int, len: Int ): Unit = _init(bs,off,len)
  def reset: Unit = _reset
}

/**
  * a wrapper for ASCII data that allows to iterate over the data returning chars
  *
  * we don't use Slice for this since those can also represent arbitrary binary data and we
  * want to make it explicit our data only contains ASCII chars. It's fine to process a
  * ASCIICharSequence as a Slice but not necessarily the other way
  *
  * TODO - not clear if private[this] var fields really pay off performance-wise (they locally
  * use getfield insns instead of invokevirtual). They are ugly and error prone
  */
sealed abstract class ASCIICharSequence extends CharSequence with ByteRange {

  // those are considered immutable here (except of from _init)
  private[this] var _data: Array[Byte] = null
  private[this] var _offset: Int = 0
  private[this] var _length: Int = 0

  //--- ByteRange accessors
  def data: Array[Byte] = _data
  def offset: Int = _offset
  def byteLength: Int = _length

  // CharSequence (char) length
  override def length: Int = _length

  // only to be used by mutable subclasses
  protected def _init (bs: Array[Byte], off: Int, len: Int): Unit = {
    _data = bs
    _offset = off
    _length = len
  }

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

  //--- these are the performance critical methods
  final def charAt(i: Int): Char = (_data(i+_offset) & 0xff).toChar
  final def apply(i: Int): Char = (_data(i+_offset) & 0xff).toChar

  override def subSequence(start: Int, end: Int): CharSequence = {
    if (start >= _length || end > _length) throw new IndexOutOfBoundsException(s"subSequence $start..$end out of bounds 0..${_length}")
    new ConstASCIICharSequence(_data, _offset+start, end-start)
  }

  override def toString: String = new String(_data,_offset,_length)

  override def chars: IntStream = {
    StreamSupport.intStream(
      () => Spliterators.spliterator(new ASCIICharIterator(_data,_offset,_offset+_length), _length, Spliterator.ORDERED),
      Spliterator.SUBSIZED | Spliterator.SIZED | Spliterator.ORDERED,
      false)
  }

  override def codePoints: IntStream = chars

  def toSlice: Slice = new Slice(_data,_offset,_length)
}

class ConstASCIICharSequence (bs: Array[Byte], off:Int, len: Int) extends ASCIICharSequence {
  _init(bs,off,len)

  def this (bs: Array[Byte]) = this(bs,0,bs.length)
  def this (slice: Slice) = this(slice.data, slice.offset, slice.byteLength)
}

class MutASCIICharSequence extends ASCIICharSequence {
  def initialize (bs: Array[Byte], off: Int, len: Int ): Unit = _init(bs,off,len)
  def reset: Unit = {} // to keep it similar to MutUTF8CharSequence
}