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

import java.nio.ByteBuffer


class CsvParseException (msg: String) extends RuntimeException(msg)

/**
  * slice based pull parser for CSV as defined in https://tools.ietf.org/html/rfc4180
  */
trait CsvPullParser {

  protected var data: Array[Byte] = Array.empty[Byte]  // the (abstract) multi-line data buffer, might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  val value = MutUtf8Slice.empty
  var nValues = 0

  protected def setData (newData: Array[Byte]): Unit = setData(newData,newData.length, 0)

  protected def setData (newData: Array[Byte], newLimit: Int, newIdx: Int = 0): Unit = {
    data = newData
    limit = newLimit
    value.data = newData
    idx = newIdx
  }

  def clear(): Unit = {
    idx = 0
    limit = 0
    value.clear()
    nValues = 0
  }

  def hasMoreData: Boolean = idx < limit

  //--- the public methods

  // idx is on start of first value, the preceding ',', or on EOL
  def parseNextValue(): Boolean = {
    val data = this.data
    var i = idx
    var iStart = -1

    value.clearRange()

    if (i >= limit || isRecordSeparator(data(i))) return false

    if (data(i) == ',' && nValues > 0) i += 1

    if (data(i) == '"') {
      iStart = i+1
      i = skipToEndOfStringValue(iStart)
      idx = i+1

    } else {
      iStart = i
      i = skipToSeparator(iStart)
      idx = i
    }

    value.setRange(iStart,i-iStart)
    nValues += 1
    true
  }

  def skip (nSkip: Int): Unit = {
    val data = this.data
    var i = idx
    var n = 0

    while (i < limit && n < nSkip) {
      if (isRecordSeparator(data(i))) {
        idx = i
        return
      }

      if (data(i) == ',') {
        i += 1
        nValues += 1
      }

      if (data(i) == '"') {
        i = skipToEndOfStringValue(i + 1) + 1
      } else {
        i = skipToSeparator(i)
      }
      n += 1
    }
    idx = i
  }

  @inline final def parseNextNonEmptyValue(): Boolean = parseNextValue() && value.nonEmpty

  // note data(idx) is the separator, i.e. callers might still have to advance
  def skipToEndOfRecord(): Unit = {
    val data = this.data
    var i = idx

    while (i<limit && !isRecordSeparator(data(i))) {
      if (data(i) == '"') i = skipToEndOfStringValue(i+1)
      i += 1
      if (idx >= limit) acquireMoreData()
    }

    idx = i
  }

  def skipToLimit(): Unit = {
    idx = limit
  }

  // override this if the parser is able to obtain more data
  protected def acquireMoreData() = false

  // TODO - do we want to support ignoring empty lines?
  def skipRecordSeparator (): Boolean = {
    if (idx < limit && isRecordSeparator(data(idx))) idx +=1
    (idx < limit)
  }

  @inline def skipLine(): Boolean = skipRecordSeparator()

  def skipToNextRecord(): Boolean = {
    nValues = 0
    skipToEndOfRecord()

    while (idx < limit && isRecordSeparator(data(idx))) {
      idx += 1
      if (idx >= limit) acquireMoreData()
    }

    idx < limit
  }

  def hasMoreValues: Boolean = isValueSeparator(data(idx))

  def readNextValue(): Utf8Slice = if (parseNextValue()) value else throw new CsvParseException("no value left")

  def isLineStart: Boolean = nValues == 0

  //--- internals

  @inline def isRecordSeparator(b: Byte): Boolean = (b == '\r' || b == '\n')
  @inline def isValueSeparator(b: Byte): Boolean = (b == ',')
  @inline def isSeparator (b: Byte): Boolean = isValueSeparator(b) || isRecordSeparator(b)

  def skipToEndOfStringValue (i0: Int): Int = {
    val data = this.data
    var i = i0
    while (i < limit){
      if (data(i) == '"') {
        if (data(i + 1) == '"') i += 2  // escaped double-quote
        else return i
      } else i = i+1
    }
    i
  }

  def skipToSeparator (i0: Int): Int = {
    val data = this.data
    var i = i0
    while (i < limit && !isSeparator(data(i))) i = i+1
    i
  }

  def skipRecordSeparator (i0: Int): Int = {
    val data = this.data
    var i = i0
    while (i < limit && isRecordSeparator(data(i))) i += 1
    i
  }

  //--- debugging
  def dumpNext(n: Int): String = {
    val i1 = Math.min( idx+n, limit)
    new String(data,idx,i1-idx)
  }
}


/**
  * unbuffered CsvPullParser processing String input
  */
trait StringCsvPullParser extends CsvPullParser {
  def initialize (s: String): Boolean = {
    clear()
    setData(s.getBytes)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }
}


/**
  * buffered CsvPullParser processing String input
  */
class BufferedStringCsvPullParser (initBufSize: Int = 8192) extends CsvPullParser {

  protected val bb = new Utf8Buffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear()
    setData(bb.data, bb.len)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }
}

/**
  * buffered CsvPullParser processing ASCII String input
  */
class BufferedAsciiStringCsvPullParser(initBufSize: Int = 8192) extends CsvPullParser {

  protected val bb = new AsciiBuffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear()
    setData(bb.data, bb.len)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }
}

/**
  * unbuffered CsvPullParser processing utf-8 byte array input
  */
trait Utf8CsvPullParser extends CsvPullParser {
  def initialize (bs: Array[Byte], limit: Int, start: Int=0): Boolean = {
    clear()
    setData(bs,limit,start)

    idx = skipRecordSeparator(start)
    (idx < limit)
  }

  def initialize (bs: Array[Byte]): Boolean = initialize(bs,bs.length, 0)
  def initialize (slice: ByteSlice): Boolean = initialize( slice.data, slice.off+slice.len, slice.off)
  def initialize (bb: ByteBuffer): Boolean = initialize( bb.array(), bb.limit(), bb.position())
}

