/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import java.lang.System.arraycopy
import java.io.{InputStream, InputStreamReader}

/**
  * a stream which can search for string patterns in underlying byte streams
  *
  * this is mostly an optimization that tries to minimize runtime cost under the
  * assumptions that
  *   - streams are large,
  *   - have many matches,
  *   - search string length << buffer/stream length
  *
  * note the patterns are simple strings, not Regexes, since we need to efficiently
  * match pattern prefixes at the end of the read buffer
  */
class SearchStream (istream: InputStream, bufferLength: Int = 4096) {

  /**
    * cache for read results
    *
    * NOTE - field values are not persistent over multiple readTo calls, use the
    * asX() converters to save results
    */
  class ReadResult (var cs: Array[Byte], var startIdx: Int, var len: Int) {

    @inline final def asString: String =  new String(cs,startIdx, len)
    @inline final def asString (off: Int): String = new String(cs,startIdx + off, len-off)

    @inline final def asArray: Array[Byte] = asArray(0)
    @inline final def asArray (off: Int=0): Array[Byte] = {
      val l = len - off
      val a = new Array[Byte](l)
      arraycopy(cs,startIdx + off, a,0, l)
      a
    }

    @inline def endIdx = startIdx + len

    @inline def isSuccess = len > 0

    //--- internal use

    @inline private[SearchStream] def clear: Unit = len = 0

    @inline private[SearchStream] def set (a: Array[Byte], i: Int, l: Int): Boolean = {
      cs = a
      startIdx = i
      len = l
      true
    }
  }

  protected var done = false

  //--- the read buffer
  protected var buf = new Array[Byte](bufferLength)
  protected var nAvail = 0 // length of data in buf
  protected var maxRead = 0 // length of *available* data in buf (nAvail - current pattern prefix at end)
  protected var iLast = 0 // current start position into read buffer

  //--- on-demand buffer to accumulate data that spans multiple read buffer fills
  protected var sBuf: Array[Byte] = null
  protected var sLen = 0 // number of chars in sBuf

  /** where to store the data of a readTo */
  val readResult = new ReadResult(null,0,0)

  private def ensureBufferLength (b: Array[Byte], maxLen: Int, curDataSize: Int): Array[Byte] = {
    if (b == null){
      new Array[Byte](maxLen)
    } else {
      if (b.length < maxLen) {
        val newB = new Array[Byte](maxLen)
        arraycopy(b, 0, newB, 0, curDataSize)
        newB
      } else b // nothing to change
    }
  }

  @inline private def clearSbuf = sLen = 0

  @inline private def appendSbuf (i: Int, len: Int): Unit = {
    if (len > 0) {
      sBuf = ensureBufferLength(sBuf, sLen + len, sLen)
      arraycopy(buf, i, sBuf, sLen, len)
      sLen += len
    }
  }

  private def fillBuffer (s: BMSearch): Unit = {
    buf = ensureBufferLength(buf,s.patternLength * 2,maxRead)
    iLast = 0 // no matter what - after a refill we start at the beginning of the read buffer
    val leftOver = nAvail - maxRead

    //--- left shift trailing leftOver and fill available buffer space
    if (leftOver > 0){
      arraycopy(buf,maxRead, buf,0, leftOver)
      val n = istream.read(buf,leftOver,buf.length-leftOver)
      if (n < 0) { // shortcut - only previous leftOver is left to read
        nAvail = leftOver
        maxRead = leftOver
        // not done yet
      } else {
        nAvail = leftOver + n
        maxRead = readBarrier(s)
      }
    } else { // there was no leftOver, try to fill whole buffer
      val n = istream.read(buf,0,buf.length)
      if (n < 0) {  // shortcut - nothing left to read
        nAvail = 0
        maxRead = 0
        done = true
      } else {
        nAvail = n
        maxRead = readBarrier(s)
      }
    }
  }

  private def readBarrier (pattern: BMSearch): Int = {
    val i = pattern.indexOfLastPatternPrefixIn(buf,0,nAvail)
    if (i < 0) nAvail else i
  }

  //--- the public API

  /**
    * read data from current position up to the next match or end of the stream. Make data available
    * in 'readResult'
    *
    * @param pattern to search for
    * @param off optional offset from current position where to start the search
    * @return true if match was found
    */
  def readTo (pattern: BMSearch, off: Int=0): Boolean = {
    readResult.clear
    if (!done) {
      maxRead = readBarrier(pattern)
      clearSbuf
      if (maxRead == 0) fillBuffer(pattern) // nothing read yet

      var i = pattern.indexOfFirstInRange(buf, iLast + off, maxRead)

      while (!done) {
        if (i < 0) { // no pattern occurrence found in buffer, save and refill
          appendSbuf(iLast, maxRead - iLast)
          fillBuffer(pattern)

          if (maxRead == 0) { // nothing left to read, bail out
            return if (sLen == 0) false else readResult.set(sBuf,0,sLen)
          }

        } else { // found match
          val i0 = iLast
          val len = i - i0
          iLast = i

          return if (sLen == 0) { // no need to copy, whole slice is within same buffer fill
            readResult.set(buf,i0,len)
          } else {
            appendSbuf(i0, len)
            readResult.set(sBuf,0,sLen)
          }
        }
        i = pattern.indexOfFirstInRange(buf, iLast, maxRead)
      }
    }
    false
  }

  /**
    * search stream from current position up to next match or end of stream
    *
    * @param pattern to search for
    * @param off optional offset from current position where to start the search
    * @return true if match was found
    */
  def skipTo (pattern: BMSearch, off: Int = 0): Boolean = {
    if (!done) {
      maxRead = readBarrier(pattern)
      var i = pattern.indexOfFirstInRange(buf, iLast + off, maxRead)

      while (!done) {
        if (i < 0) {
          fillBuffer(pattern)
        } else {
          iLast = i
          return true
        }
        i = pattern.indexOfFirstInRange(buf, iLast, maxRead)
      }
    }
    false
  }

  def close = {
    done = true
    istream.close
  }

  def hasMoreData = !done
}

