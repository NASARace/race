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

import gov.nasa.race.common.inlined.Slice

class CsvParseException (msg: String) extends RuntimeException(msg)

/**
  * slice based pull parser for CSV as defined in https://tools.ietf.org/html/rfc4180
  */
abstract class CsvPullParser {

  protected var data: Array[Byte] = Array.empty[Byte]  // the (abstract) multi-line data buffer, might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  val value = Slice.empty
  var nValues = 0

  protected def setData (newData: Array[Byte]): Unit = setData(newData,newData.length)

  protected def setData (newData: Array[Byte], newLimit: Int): Unit = {
    data = newData
    limit = newLimit
    value.data = newData
  }

  def clear: Unit = {
    idx = 0
    limit = 0
    value.clear
    nValues = 0
  }

  //--- the public methods

  // idx is on start of first value, the preceding ',', or on EOL
  def parseNextValue: Boolean = {
    val data = this.data
    var i = idx
    var iStart = -1

    value.clearRange

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

  def skip (nValues: Int): Unit = {
    val data = this.data
    var i = idx
    var n = 0

    while (i < limit && n < nValues) {
      if (isRecordSeparator(data(i))) {
        idx = i
        return
      }

      if (data(i) == ',') i += 1

      if (data(i) == '"') {
        i = skipToEndOfStringValue(i + 1) + 1
      } else {
        i = skipToSeparator(i)
      }
      n += 1
    }
    idx = i
  }

  def skipToEndOfRecord: Unit = {
    val data = this.data
    var i = idx

    while (i<limit && !isRecordSeparator(data(i))) {
      if (data(i) == '"') i = skipToEndOfStringValue(i+1)
      i += 1
    }
    idx = i
  }

  def skipToNextRecord: Boolean = {
    if (idx < limit) {
      if (isRecordSeparator(data(idx))) idx = skipRecordSeparator(idx+1)
      nValues = 0
      true
    } else {
      false
    }
  }

  def hasMoreValues: Boolean = isValueSeparator(data(idx))

  def readNextValue: Slice = if (parseNextValue) value else throw new CsvParseException("no value left")

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
}


/**
  * unbuffered CsvPullParser processing String input
  */
class StringCsvPullParser extends CsvPullParser {
  def initialize (s: String): Boolean = {
    clear
    setData(s.getBytes)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }
}


/**
  * buffered CsvPullParser processing String input
  */
class BufferedStringCsvPullParser (initBufSize: Int = 8192) extends CsvPullParser {

  protected val bb = new UTF8Buffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear
    setData(bb.data, bb.length)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }
}

/**
  * buffered CsvPullParser processing ASCII String input
  */
class BufferedASCIIStringCsvPullParser (initBufSize: Int = 8192) extends CsvPullParser {

  protected val bb = new ASCIIBuffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear
    setData(bb.data, bb.length)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }
}

/**
  * unbuffered CsvPullParser processing utf-8 byte array input
  */
class UTF8CsvPullParser extends CsvPullParser {
  def initialize (bs: Array[Byte], limit: Int): Boolean = {
    clear
    setData(bs,limit)

    idx = skipRecordSeparator(0)
    (idx < limit)
  }

  def initialize (bs: Array[Byte]): Boolean = initialize(bs,bs.length)
}

