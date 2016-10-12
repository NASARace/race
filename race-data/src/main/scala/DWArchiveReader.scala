/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.data

import java.io._
import java.util.zip.GZIPInputStream
import java.lang.{StringBuilder => JStringBuilder}

import gov.nasa.race.common.{BMSearch, StringUtils}
import org.joda.time.DateTime

object DWArchiveReader {
  final val BufferLength = 8192
  final val FirstStartPattern = "<properties>"
  final val StartPattern = "\n<properties>"
  final val EndPattern = "</properties>"
}
import DWArchiveReader._

/**
  * a specialized ArchiveReader for DataWarehouse formats, which all use
  *    "<properties>... timestamp ...</properties>"
  * prefixe elements that only contain CDATA and start on newlines (no leading spaces). The data is assumed to be
  * stored compressed.
  *
  * DWArchiveReaders do differ in terms of how the timestamp is retrieved from the properties CDATA, and if/how
  * newlines are used in the XML message payload, which is specific to the respective topic (sfdps,asdex,tfmdata etc.).
  *
  * This implementation tries to avoid extra String allocation, to minimize heap pressure for formats that do
  * use newlines (such as sfdps)
  *
  * Note that we can't use memory mapped files here since the external (disk) data can be compressed. This unfortunately
  * means the data will be copied.
  *
  * NOTE ALSO that we assume the <properties> header is smaller than DWArchiveReader.BufferLength
  */
abstract class DWArchiveReader (val istream: InputStream) extends ArchiveReader {

  protected var buf = new Array[Char](BufferLength)
  protected val reader = new BufferedReader(new InputStreamReader(istream),BufferLength)
  protected var nRead = 0

  protected val propStart = new BMSearch(StartPattern)
  protected val propEnd = new BMSearch(EndPattern)

  protected var isFirstMsg = true

  def readLong(i:Int): Long = {
    val cs = buf
    var c = cs(i)
    var j = i
    var n = 0L
    while (j < cs.length && Character.isDigit(c)){
      n = n * 10 + (c - '0')
      j += 1
      c = cs(j)
    }
    n
  }

  def readString(i: Int): String = {
    val cs = buf
    var c = cs(i)
    var j = i
    while (j < cs.length && !Character.isWhitespace(c)){
      j += 1
      c = cs(j)
    }
    if (j > i) new String(buf,i,j-i) else ""
  }

  def readDateTime(i0: Int, i1: Int) : DateTime // to be provided by concrete subclasses

  override def read: Option[ArchiveEntry] = {
    //--- if this is the first call, fill the buffer and check if it begins with a startPattern
    if (isFirstMsg){
      nRead = reader.read(buf, 0, buf.length)
      if (nRead < 0 || !StringUtils.startsWith(buf,0,FirstStartPattern)) return None
      isFirstMsg = false
    }

    //--- the buffer starts with a startPattern - get the end pattern and then look for the datePattern within this range
    // we assume the header will fit completely into the buffer
    var i1 = propEnd.indexOfFirst(buf, StartPattern.length)
    if (i1 < 0) throw new RuntimeException("no end pattern") // return None // error - no end pattern found in buffer

    val date = readDateTime(StartPattern.length, i1)

    //--- now copy all between end pattern and next start pattern into msg
    val msg = new JStringBuilder // to collect msg payload

    i1 += EndPattern.length
    var i0 = propStart.indexOfFirst(buf,i1)
    while (i0 < 0){
      msg.append(buf,i1,nRead - i1)
      nRead = reader.read(buf, 0, buf.length)
      if (nRead < 0) { // done
        if (msg.length > 0 && date != null) return Some(ArchiveEntry(date,msg.toString)) else return None
      }
      i0 = propStart.indexOfFirst(buf)
      i1 = 0
    }
    msg.append(buf,i1,i0- i1)

    nRead -= i0
    System.arraycopy(buf,i0,buf,0,nRead)  // shift buffer left so that it begins with unread start pattern
    val nRead1 = reader.read(buf,nRead,buf.length-nRead) // fill up buffer
    if (nRead1 > 0) nRead += nRead1
    if (date != null) Some(ArchiveEntry(date,msg.toString)) else None
  }
}

class AsdexDWArchiveReader (istream: InputStream) extends DWArchiveReader(istream) {
  val dexEpoch = new BMSearch("DEX_TIMESTAMP=")
  val jmsEpoch = new BMSearch("JMS_BEA_DeliveryTime=")
  val tsDTG = new BMSearch("timestamp=")

  override def readDateTime (i0: Int, i1: Int) = {
    var i = jmsEpoch.indexOfFirst(buf,i0,i1)
    if (i >= 0) {
      new DateTime(readLong(i+jmsEpoch.pattern.length))
    } else {
      i = dexEpoch.indexOfFirst(buf,i0,i1)
      if (i >= 0) {
        new DateTime(readLong(i+dexEpoch.pattern.length))
      } else  {
        i = tsDTG.indexOfFirst(buf,i0,i1)
        if (i >= 0) DateTime.parse(readString(i+tsDTG.pattern.length)) else null
      }
    }
  }
}

class JmsBeaDWArchiveReader (istream: InputStream) extends DWArchiveReader(istream) {
  val jmsEpoch = new BMSearch("JMS_BEA_DeliveryTime=")

  override def readDateTime (i0: Int, i1: Int) = {
    val i = jmsEpoch.indexOfFirst(buf, i0, i1)
    if (i >= 0) {
      new DateTime(readLong(i + jmsEpoch.pattern.length))
    } else null
  }
}

class SfdpsDWArchiveReader(istream: InputStream) extends JmsBeaDWArchiveReader(istream)
class TfmdataDWArchiveReader(istream: InputStream) extends JmsBeaDWArchiveReader(istream)


