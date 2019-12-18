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

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CoderResult, StandardCharsets}

import gov.nasa.race.util.JUtils

abstract class StringDataBuffer (initDataSize: Int) {
  var data: Array[Byte] = new Array(initDataSize)
  var length: Int = 0

  def encode (s: String): Int = {
    length = 0
    this += s
    length
  }

  def apply (i: Int): Byte = data(i)
  def clear: Unit = length = 0

  //--- those are the two workhorses that have to be defined in subclasses
  def += (s: String): Unit
  def += (c: Char): Unit

  protected def ensureCapacity(len: Int): Unit = {
    if (len > data.length) {
      val newData = new Array[Byte] (len)
      if (length > 0) System.arraycopy (data, 0, newData, 0, length)
      data = newData
    }
  }
}

/**
  * utility class that basically does a String.getBytes() into a re-usable buffer so that we
  * can save per-call allocation
  *
  * Note - this is very slow since it maps to per-char charAt(idx) calls
  */
class UTF8Buffer (initDataSize: Int = 8192) extends StringDataBuffer(initDataSize) {

  def += (s: String): Unit = {
    val len = length + UTFx.utf8Length(s)
    ensureCapacity(len)

    var i = length
    var enc = UTFx.initUTF8Encoder(s,0)
    while (!enc.isEnd) {
      data(i) = enc.utf8Byte
      i += 1
      enc = enc.next(s)
    }
    length = len
  }

  def += (c: Char): Unit = {
    val len = length + UTFx.utf8Length(c)
    ensureCapacity(len)

    var i = length
    var enc = UTFx.initUTF8Encoder(c)
    while (!enc.isEnd) {
      data(i) = enc.utf8Byte
      i += 1
      enc = enc.next(c)
    }
    length = len
  }
}

/**
  * utility class to write ASCII String contents into a re-usable buffer
  *
  * NOTE - this uses a deprecated String method which only discards the high byte of a
  * chars, i.e. it does not use a proper CharsetEncoder
  */
class ASCIIBuffer (initDataSize: Int = 8192) extends StringDataBuffer(initDataSize) {

  def += (s: String): Unit = {
    val len = length + s.length
    ensureCapacity(len)
    //s.getBytes(0,len,data,0)  // this is safe since we only call this on ASCII strings (and we have to avoid allocation)
    JUtils.getASCIIBytes(s,0,s.length,data,length) // FIXME - this is just to suppress the deprecated warning (Scala can't)
    length = len
  }

  def += (c: Char): Unit = {
    val len = length+1
    ensureCapacity(len)
    data(length) = c.toByte
    length = len
  }
}