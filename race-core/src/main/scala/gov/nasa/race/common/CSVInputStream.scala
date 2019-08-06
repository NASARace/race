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

import java.io.{EOFException, InputStream}

/**
  * a InputStream that reads CSV fields, avoiding a underlying BufferedInputStreamReader
  *
  * CSVInputStream instances are /not/ threadsafe
  *
  * note this only supports ASCII text for now
  */
class CSVInputStream (in: InputStream, bufLen: Int = 1024) {

  final val FieldSep = ','
  final val RecSep = '\n'
  
  protected val buf: Array[Byte] = new Array(bufLen)

  protected var pos: Int = 0 // the next position to read
  protected var end: Int = 0

  protected var endOfRecord = false

  def close: Unit = {
    in.close
  }

  def reset: Unit = {
    in.reset
    pos = 0
    end = 0
  }

  def available = in.available

  def fill: Boolean = {
    var start = 0
    if (pos < end) { // bytes left in buffer
      start = end - pos
      System.arraycopy(buf,pos, buf,0, start)
    }

    val n = in.read(buf, start, buf.length - start)

    pos = 0
    end = if (n < 0) start else pos + n

    if (end > pos) {
      endOfRecord = true
      true
    } else {
      false
    }
  }

  @inline final def wasEndOfRecord: Boolean = endOfRecord

  @inline final def isSeparator (c: Int): Boolean = {
    c == FieldSep || c == RecSep
  }

  @inline final def isOnFieldSep: Boolean = if (hasNext) buf(pos) == FieldSep else false

  @inline final def isOnRecSep: Boolean = if (hasNext) buf(pos) == RecSep else false

  @inline final def checkAndSkipSeparator(c: Int): Boolean = {
    if (c == FieldSep) {
      pos += 1
      true
    } else if (c == RecSep){
      pos += 1
      endOfRecord = true
      true
    } else if (c == '\r') {
      pos += 2
      true
    } else {
      false
    }
  }

  @inline def testAndSkipSep: Boolean = if (hasNext) checkAndSkipSeparator(buf(pos)) else false


  @inline final def hasNext: Boolean = {
    if (pos >= end) fill else true
  }

  @inline final def hasNextDataByte: Boolean = {
    if (pos >= end && !fill) {
      false
    } else {
      !isSeparator(buf(pos))
    }
  }

  @inline final def isDigit (c: Int): Boolean = c >= '0' && c <= '9'

  @inline final def ensureData: Unit = {
    if (!hasNext) throw new EOFException
  }

  @inline final def readOptionalSignum: Int = {
    if (hasNext) {
      val b = buf(pos) & 0xff

      if (b == '-') {
        pos += 1
        -1
      } else {
        if (b == '+') pos += 1
        1
      }
    } else 1
  }

  def readLong: Long = {
    endOfRecord = false
    val sig = readOptionalSignum
    var l: Long = 0
    ensureData
    do {
      val b: Int = buf(pos)
      if (isDigit(b)) {       // data byte
        l = l * 10 + (b-'0')
      } else {
        if (checkAndSkipSeparator(b)){  // non data byte
          return sig * l
        } else {
          throw new NumberFormatException
        }
      }
      pos += 1
    } while (hasNext)

    sig * l
  }
  @inline final def readOptionalLong: Long = if (checkAndSkipSeparator(buf(pos))) 0 else readLong

  @inline def readInt: Int = readLong.toInt // ?? maybe we should throw an exception if out of range

  def readDouble: Double = {
    endOfRecord = false
    val sig = readOptionalSignum
    ensureData
    var n: Long = 0
    var d: Double = 0.0
    var e: Double = 1.0
    var b: Int = 0

    //--- integer part
    do {
      b = buf(pos)
      if (isDigit(b)) {
        n = n * 10 + (b - '0')
        pos += 1
      }
    } while (hasNext && b != '.' && (b|32) != 'e' && !isSeparator(b))

    //--- fractional part
    if (b == '.'){
      pos += 1
      ensureData
      var m: Double = 10.0
      do {
        b = buf(pos)
        if (isDigit(b)) {
          d = d + (b - '0')/m
          m *= 10
          pos += 1
        }
      } while (hasNext && (b|32) != 'e' && !isSeparator(b))
    }

    //--- exponent part
    if ((b|32) == 'e'){
      pos += 1
      val exp = readLong
      e = Math.pow(10.0, exp.toDouble)
    } else {
      if (hasNext && !checkAndSkipSeparator(b)) throw new NumberFormatException
    }

    sig * (n + d)*e
  }
  @inline final def readOptionalDouble: Double = if (checkAndSkipSeparator(buf(pos))) 0.0 else readDouble

  protected def quoted(b: Byte): Byte = {
    b match {
      case '\\' => '\\'
      case 'n' => RecSep
      case '"' => '"'
      case _ => b
    }
  }

  // current pos if on opening '"'
  protected def readQuotedString: String = {
    var quote: Boolean = false
    var sBuf = new Array[Byte](512)
    var sBufLen = 0
    var b: Byte = 0
    var done = false

    def _ensureBufLen: Unit = {
      if (sBufLen >= sBuf.length){
        val sb = new Array[Byte](sBuf.length*2)
        System.arraycopy(sBuf,0,sb,0,sBufLen)
        sBuf = sb
      }
    }

    do {
      pos += 1
      if (pos >= end) {
        if (!fill) return new String(sBuf,0,sBufLen)
      }
      b = buf(pos)
      if (b == '\\') {
        quote = true
      } else if (quote) {
        quote = false
        _ensureBufLen
        sBuf(sBufLen) = quoted(b)
        sBufLen += 1
      } else if (b != '"') {
        _ensureBufLen
        sBuf(sBufLen) = b
        sBufLen += 1
      } else {
        done = true
      }

    } while (!done)

    do {
      pos += 1
    } while (hasNext && !isSeparator(buf(pos)))
    pos += 1

    new String(sBuf,0,sBufLen)
  }


  def readString: String = {
    endOfRecord = false
    var p0: Int = pos

    var sBuf: Array[Byte] = null
    var sBufLen: Int = 0

    def _appendToSbuf: Unit = {
      val len = end - p0
      if (sBuf == null) {
        sBuf = new Array(Math.max(512, len))
      } else {
        if (sBuf.length - sBufLen < len) {
          val sb = new Array[Byte](sBufLen + len)
          System.arraycopy(sBuf,0,sb,0,sBufLen)
          sBuf = sb
        }
      }
      System.arraycopy(buf,p0,sBuf,sBufLen,len)
      sBufLen += len
    }

    ensureData

    if (buf(pos) == '"'){
      // this is handled separately since it supports quoting and hence needs to use an intermediate buffer
      readQuotedString

    } else {
      do {
        pos += 1
        if (pos >= end) {
          _appendToSbuf // we have to copy to sbuf first since fill might overwrite buf
          if (!fill) return new String(sBuf,0,sBufLen)
          p0 = 0
        }
      } while (!isSeparator(buf(pos)))
      pos += 1

      if (sBuf != null) {
        _appendToSbuf
        new String(sBuf,0,sBufLen)
      } else {
        new String(buf,p0,(pos - p0 -1))
      }
    }
  }
  @inline final def readOptionalString: String = if (checkAndSkipSeparator(buf(pos))) "" else readString


  def skipField: Unit = {
    while (hasNext){
      if (isSeparator(buf(pos))){
        pos += 1
        return
      }
      pos += 1
    }
  }

  // skip n fields, but only within the same record
  def skipFields (n: Int): Unit = {
    var i = 0

    while (hasNext) {
      val b = buf(pos)
      if (b == RecSep){
        pos += 1
        return
      } else if (b == FieldSep) {
        i += 1
        if (i == n) {
          pos += 1
          return
        }
      }
      pos += 1
    }
  }

  def skipRecord: Unit = {
    while (hasNext){
      if (buf(pos)== RecSep){
        pos += 1
        return
      }
      pos += 1
    }
  }


  // note this can only match up to buf.length bytes
  def matchBytes (bs: Array[Byte]): Boolean = {
    val len = bs.length
    if (end - pos < len) {
      if (!fill || (end - pos) < len) return false
    }

    var i = pos
    var j = 0
    while (j < len) {
      if (buf(i) != bs(j)) return false
      i += 1
      j += 1
    }

    pos += len // don't change pos before we know it's a match
    true
  }
}
