/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import java.io.InputStream
import scala.annotation.tailrec

/**
  * a buffer for line-oriented InputStreams, used as a basis for byte array based parsers.
  * the buffer always holds at least one line (if there is one left)
  *
  * This is a more efficient alternative to using BufferedInputStream and tokenizers that extract strings
  */
class LineBuffer (val is: InputStream, val maxBufsize: Int = Int.MaxValue, val initBufsize: Int=4096) extends ByteSlice {

  protected var buf: Array[Byte] = new Array[Byte](initBufsize)  // has to be var in case current buf is not long enough to hold line
  protected var recStart: Int = 0 // start of current line
  protected var recLimit: Int = 0 // (exclusive) end index of current line
  protected var dataLimit: Int = 0 // (exclusive) end index of data in buf
  protected var isEnd = false // no more data in InputStream

  //--- ByteSlice interface
  def data: Array[Byte] = buf
  def len: Int = recLimit - recStart // length of the current record
  def off: Int = recStart

  def dataLength: Int = dataLimit

  /**
    * free all resources held by this buffer and mark it as finished
    */
  def close(): Unit = {
    buf = Array.empty[Byte]
    recStart = 0
    recLimit = 0
    dataLimit = 0
    isEnd = true
  }

  def hasReachedEnd: Boolean = isEnd

  protected final def getRecLimit (i0: Int): Int = {
    val lim = dataLimit
    val bs = buf

    var i = i0
    while (i < lim) {
      val b = bs(i)
      if (b == '"') {
        i = skipQuoted(i)
      } else {
        if (b == '\r' || b == '\n') return i  // whatever comes first. Unfortunately they can appear single or combined as \r\n
        i += 1
      }
    }
    if (isEnd) i else -1 // no \n in tail of buffer
  }

  def skipQuoted (i0: Int): Int = {
    val lim = dataLimit
    val bs = buf
    var i=i0
    while (i < lim) {
      val b = bs(i)
      if (b == '"') {
        i += 1
        if (i < lim && bs(i) != '"') return i
      }
      i += 1
    }
    i
  }

  protected def growBuffer(): Unit = {
    val newLen = buf.length * 2
    if (newLen > maxBufsize) throw new RuntimeException("max line buffer size exceeded")

    val newData = new Array[Byte](newLen)
    System.arraycopy(buf, 0, newData, 0, dataLimit)
    buf = newData
  }

  @tailrec protected final def fetchMoreData(): Unit = {
    val nFree = buf.length - dataLimit
    val nRead = is.read(buf,dataLimit,nFree)
    if (nRead > 0) {
      dataLimit += nRead
    } else {
      if (nRead < 0){
        isEnd = true
      } else {
        fetchMoreData()
      }
    }
  }

  protected def fillBuffer(): Unit = {
    if (dataLimit > recLimit) {  // left-shift remaining data to beginning of buffer
      if (recStart > 0) {
        val nRemaining = dataLimit - recStart
        System.arraycopy(buf, recStart, buf, 0, nRemaining)
        dataLimit = nRemaining
      }

    } else { // we consumed all data that was in the buffer, start from beginning
      dataLimit = 0
    }

    recStart = 0
    fetchMoreData()
  }

  def getRecStart(): Int = {
    if (nLines == 0) { // first line - don't skip anything
      0
    } else {  // we must have had a previous recLimit
      var i = recLimit
      val b = buf(i)
      if (b == '\r') {  // skip over optionally following '\n'
        i += 1
        if (i == dataLimit) {
          recLimit = i // make sure fillBuffer copies the remainder
          fillBuffer()
          i = 0
        }
        if (buf(i) == '\n') { // bingo - the accompanying '\n' was in the next chunk
          i+1
        } else { // this was a single '\r'
          i
        }
      } else if (b == '\n') { // a single '\n'
        i+1
      } else {
        throw new RuntimeException("no previous line terminator") // can't get here - if this wasn't the first line there had to be a terminator
      }
    }
  }

  protected var nLines = 0

  def nextLine(): Boolean = {
    if (!isEnd) {
      if (recLimit == dataLimit) { // buffer not yet initialized or fully consumed
        fillBuffer()
        if (isEnd && dataLimit <= recStart) return false // no more data
      }

      recStart = getRecStart()
      recLimit = getRecLimit(recStart)

      while (recLimit < 0) { // buf has only partial data of next record
        val nLeft = dataLimit - recStart
        fillBuffer() // this always resets recStart
        recLimit = getRecLimit(nLeft)
        if (recLimit < 0) growBuffer()
      }

      if (dataLimit > 0){
        nLines += 1
        true
      } else false
    } else false
  }
}
