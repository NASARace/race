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

trait StringDataBuffer {
  def data: Array[Byte]
  def length: Int
  def encode (s: String, off: Int=0): Int
}

/**
  * utility class that basically does a String.getBytes() into a re-usable buffer so that we
  * can save per-call allocation
  *
  * Note - this is very slow since it maps to per-char charAt(idx) calls
  */
class UTF8Buffer (initBufSize: Int = 8192) extends StringDataBuffer {
  var data: Array[Byte] = new Array(initBufSize)
  var length: Int = 0

  protected var bb: ByteBuffer = ByteBuffer.wrap(data)
  protected val enc = StandardCharsets.UTF_8.newEncoder

  protected def growBuf: Unit = {
    val newData = new Array[Byte](data.length*2)
    val newBB = ByteBuffer.wrap(newData)
    bb.rewind
    newBB.put(bb)
    bb = newBB
    data = newData
  }

  def encode (s: String, off: Int=0): Int = { // FIXME - implement offset
    val cb = CharBuffer.wrap(s)
    bb.rewind
    length = 0
    var done = false

    do {
      enc.encode(cb, bb, true) match {
        case CoderResult.UNDERFLOW => // all chars encoded
          length = bb.position
          done = true
        case CoderResult.OVERFLOW => growBuf
      }
    } while (!done)

    length
  }
}

/**
  * utility class to write ASCII String contents into a re-usable buffer
  *
  * NOTE - this uses a deprecated String method which only discards the high byte of a
  * chars, i.e. it does not use a proper CharsetEncoder
  */
class ASCIIBuffer (initBufSize: Int = 8192) extends StringDataBuffer {
  var data: Array[Byte] = new Array(initBufSize)
  var length: Int = 0

  def encode (s: String, off: Int = 0): Int = {
    val len = s.length + off
    if (len > data.length) {
      val newData = new Array[Byte](len)
      if (off > 0) System.arraycopy(data,0,newData,0,off)
      data = newData
    }
    //s.getBytes(0,len,data,0)  // this is safe since we only call this on ASCII strings (and we have to avoid allocation)
    JUtils.getASCIIBytes(s,0,len,data,off) // FIXME - this is just to suppress the deprecated warning (Scala can't)
    length = len
    len
  }
}