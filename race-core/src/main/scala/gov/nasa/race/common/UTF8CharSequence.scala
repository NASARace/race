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
  */
final class UTF8CharSequence (val data: Array[Byte], val offset: Int, val byteLength: Int) extends CharSequence {

  private var nci = 0 // next char index
  private var decoder = UTFx.initUTF8Decoder(data,offset)
  private val iMax = offset + byteLength
  private var len = -1  // computed on demand

  def this (bs: Array[Byte]) = this(bs,0,bs.length)
  def this (slice: Slice) = this(slice.data, slice.offset, slice.length)

  private def reset: Unit = {
    nci = 0
    decoder = UTFx.initUTF8Decoder(data,offset)
  }

  private def checkBounds (i: Int): Boolean = {
    if (decoder.isEnd) throw new IndexOutOfBoundsException(s"char index $i out of bounds")
    true
  }

  // char length
  override def length: Int = {
    if (len < 0) len = UTFx.utf16Length(data,offset,length)
    len
  }

  def charAt(ci: Int): Char = {// Argh, we have to start over again
    if (ci < 0) throw new IndexOutOfBoundsException(s"negative char index $ci out of bounds")
    if (ci < nci) reset // we have to start over from the beginning

    while (nci < ci){
      decoder = decoder.next(data,iMax)
      checkBounds(ci)
      nci += 1
    }
    decoder.utf16Char
  }

  // start and end a char indices
  override def subSequence(start: Int, end: Int): CharSequence = {
    if (start < 0) throw new IndexOutOfBoundsException(s"negative start char index $start out of bounds")
    if (end < start) throw new IndexOutOfBoundsException(s"end char index $end lower than start index $start")

    reset
    var biStart = 0
    while (nci < start){
      checkBounds(start)
      biStart = decoder.nextByteIndex
      decoder = decoder.next(data,iMax)
      nci += 1
    }

    while (nci < end) {
      checkBounds(end)
      decoder = decoder.next(data,iMax)
      nci += 1
    }
    val biEnd = decoder.nextByteIndex

    reset
    new UTF8CharSequence(data,biStart, biEnd-biStart)
  }

  override def toString: String = new String(data,offset,length)

  def toSlice: Slice = new Slice(data,offset,length)
}

/**
  * a wrapper for ASCII data that allows to iterate over the data returning chars
  *
  * we don't use Slice for this since those can also represent arbitrary binary data and we
  * want to make it explicit our data only contains ASCII chars. It's fine to process a
  * ASCIICharSequence as a Slice but not necessarily the other way
  */
final class ASCIICharSequence (val data: Array[Byte], val offset: Int, val length: Int) extends CharSequence with ByteRange {

  final class ASCIICharIterator extends PrimitiveIterator.OfInt {
    private var idx = offset
    private val maxIdx = offset + length

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

  def this (bs: Array[Byte]) = this(bs,0,bs.length)
  def this (slice: Slice) = this(slice.data, slice.offset, slice.length)

  @inline final def charAt(i: Int): Char = (data(i) & 0xff).toChar
  @inline final def apply(i: Int): Char = (data(i) & 0xff).toChar

  override def subSequence(start: Int, end: Int): CharSequence = {
    if (start >= length || end > length) throw new IndexOutOfBoundsException(s"subSequence $start..$end out of bounds 0..$length")
    new ASCIICharSequence(data, offset+start, end-start)
  }

  override def toString: String = new String(data,offset,length)

  override def chars: IntStream = {
    StreamSupport.intStream(
      () => Spliterators.spliterator(new ASCIICharIterator, length, Spliterator.ORDERED),
      Spliterator.SUBSIZED | Spliterator.SIZED | Spliterator.ORDERED,
      false)
  }

  override def codePoints: IntStream = chars

  def toSlice: Slice = new Slice(data,offset,length)
}