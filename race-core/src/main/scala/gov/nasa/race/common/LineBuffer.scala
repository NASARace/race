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
  * Note that we only use '\n' as line ending - the client has to strip other delimiters (such as '\r')
  * Note also that we do ignore delimiters within double quoted sections
  *
  * This is a more efficient alternative to using BufferedInputStream and tokenizers that extract strings
  */
class LineBuffer (val is: InputStream, val maxBufsize: Int = Int.MaxValue, val initBufsize: Int=4096) extends ByteSlice {

  final val RECORD_DELIMITER = '\n'

  protected var buf: Array[Byte] = new Array[Byte](initBufsize)  // has to be var in case current buf is not long enough to hold line
  protected var recStart: Int = 0 // start of current line
  protected var recLimit: Int = 0 // (exclusive) end index of current line
  protected var dataLimit: Int = 0 // (exclusive) end index of data in buf
  protected var isEnd = false // no more data in InputStream
  protected var nLines = 0 // number of lines read so far

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
        if (b == RECORD_DELIMITER) return i
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
    val newLen = buf.length + initBufsize // grow linearly, line length is not supposed to differ by orders of magnitude
    if (newLen > maxBufsize) throw new RuntimeException("max line buffer size exceeded")

    val newData = new Array[Byte](newLen)
    System.arraycopy(buf, 0, newData, 0, dataLimit)
    buf = newData
  }

  // this is the actual data acquisition from the underlying input stream
  // this only gets called after shifting residual data to the beginning of the buffer (recStart = 0), and it will only
  // return if we got at least one record (recLimit > recStart)
  @tailrec protected final def fetchMoreData(): Unit = {
    val nFree = buf.length - dataLimit
    assert(nFree > 0)

    val nRead = is.read(buf,dataLimit,nFree)  // <<<<<< this is the blocking point

    if (nRead >= 0) {
      if (nRead > 0) {
        dataLimit += nRead
        recLimit = getRecLimit(recStart)
        if (recLimit > 0) {
          return   // we got at least one record, process it
        } else { // we got no recLimit
          if (nRead == nFree) growBuffer() // we didn't have enough space for a new line
        }
      }

      if (nRead < nFree) {
        fetchMoreData()  // we didn't get enough data
      }

    } else { // nRead < 0 means EOS - NOTE this does NOT mean both ends of the socket are closed (isClosed() might return false!
      isEnd = true
    }
  }

  // this will shift residual data to head of buffer, reset recStart to 0 and update dataLimit to residual length
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

  /**
    * return true if there is a next line, in which case the ByteSlice interface has to hold the data.
    * Note that we do not include the line ending, but all other data (including '\r') is preserved
    */
  def nextLine(): Boolean = {
    while (!isEnd) {
      if (recLimit <= 0 || recLimit >= dataLimit) { // we need more data
        fillBuffer()

      } else { // check if there is another record in the buffer
        recStart = recLimit+1
        recLimit = getRecLimit(recStart)
      }

      if (recLimit > 0) {  // we got at least one non-empty line
        nLines += 1
        return true
      }
    }
    false // if we get here there is no more data
  }
}

/**
  * a LineBuffer that automatically strips '\r' from end of line in our ByteSlice implementation
  */
class CanonicalLineBuffer (is: InputStream, maxBufsize: Int = Int.MaxValue, initBufsize: Int=4096) extends LineBuffer(is, maxBufsize, initBufsize) {
  protected var _off = 0
  protected var _len = 0

  override def off: Int = _off
  override def len: Int = _len

  override def nextLine(): Boolean = {
    if (super.nextLine()) {
      _off = recStart
      _len = recLimit - recStart - (if (buf(recLimit-1) == '\r') 2 else 1)
      true
    } else false
  }
}