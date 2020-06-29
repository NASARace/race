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

import gov.nasa.race.util.JUtils

/**
  * a CharSequenceByteSlice that can be populated with strings and chars
  *
  * this is the ByteSlice analog to StringBuffer, i.e. a CharSequenceByteSlice with
  * internally modifiable 'data' and 'len' fields
  */
trait StringDataBuffer extends CharSeqByteSlice {
  var data: Array[Byte]  // allocated by concrete type
  var len: Int = 0
  val off: Int = 0       // always starts at index 0

  //--- those are the two workhorses that have to be defined in subclasses
  def += (s: String): Unit
  def += (c: Char): Unit

  def encode (s: String): Int = {
    len = 0
    this += s
    len
  }

  def clear(): Unit = len = 0

  protected def ensureCapacity (newCapacity: Int): Array[Byte] = {
    if (newCapacity > data.length) {
      val newData = new Array[Byte] (newCapacity)
      if (len > 0) System.arraycopy (data, 0, newData, 0, len)
      data = newData
    }
    data
  }
}

/**
  * a StringDataBuffer that uses utf8 encoding
  */
class Utf8Buffer (initSize: Int=4096) extends StringDataBuffer with Utf8Slice {
  var data: Array[Byte] = new Array[Byte](initSize)

  def += (s: String): Unit = {
    val newLen = len + UTFx.utf8Length(s)
    val data = ensureCapacity(newLen)

    var i = len
    var enc = UTFx.initUTF8Encoder(s,0)
    while (!enc.isEnd) {
      data(i) = enc.utf8Byte
      i += 1
      enc = enc.next(s)
    }
    len = newLen
  }

  def += (c: Char): Unit = {
    val newLen = len + UTFx.utf8Length(c)
    val data = ensureCapacity(newLen)

    var i = len
    var enc = UTFx.initUTF8Encoder(c)
    while (!enc.isEnd) {
      data(i) = enc.utf8Byte
      i += 1
      enc = enc.next(c)
    }
    len = newLen
  }

  override def createSubSequence(subOff: Int, subLen: Int): CharSeqByteSlice = {
    new ConstUtf8Slice(data, subOff, subLen)
  }
}

/**
  * a StringDataBuffer that only holds ASCII chars/bytes
  * clients are responsible for making sure added strings/chars only consist of ASCII chars
  */
class AsciiBuffer (initSize: Int=4096) extends StringDataBuffer with AsciiSlice {
  var data: Array[Byte] = new Array[Byte](initSize)

  def += (s: String): Unit = {
    val newLen = len + s.length
    val data = ensureCapacity(newLen)
    //s.getBytes(0,len,data,0)  // this is safe since we only call this on ASCII strings (and we have to avoid allocation)
    JUtils.getASCIIBytes(s,0,s.length,data,len) // FIXME - this is just to suppress the deprecated warning (Scala can't)
    len = newLen
  }

  def += (c: Char): Unit = {
    val newLen = len+1
    val data = ensureCapacity(newLen)
    data(len) = c.toByte
    len = newLen
  }

  override def createSubSequence(subOff: Int, subLen: Int): CharSeqByteSlice = {
    new ConstAsciiSlice(data, subOff, subLen)
  }
}