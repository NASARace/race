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

package gov.nasa.race.air

import java.io._

import gov.nasa.race._
import gov.nasa.race.archive.ArchiveReader
import gov.nasa.race.common.{BMSearch, SearchStream}
import org.joda.time.DateTime

object DWArchiveReader {
  final val BufferLength = 8192
  final val headerEnd = new BMSearch("</properties>")
}

/**
  * a specialized ArchiveReader for DataWarehouse formats, which all use
  *    "<properties>... timestamp ...</properties>"
  * prefixe elements that only contain text and start on newlines (no leading spaces). The data is assumed to be
  * stored compressed. Unfortunately payload messages do also contain <properties> elements, which is why we can't
  * just search for the element start and have to pass in the respective start pattern from the channel specific reader.
  *
  * DWArchiveReaders also differ in terms of how the timestamp is retrieved from the properties text, and if/how
  * newlines are used in the XML message payload, which is specific to the respective topic (sfdps,asdex,tfmdata etc.).
  *
  * This implementation tries to avoid extra String allocation, to minimize heap pressure for formats that do
  * use newlines (such as sfdps)
  *
  * Note that we can't use memory mapped files here since the external (disk) data can be compressed. This unfortunately
  * means the data will be copied.
  */
abstract class DWArchiveReader (val istream: InputStream, val headerStart: BMSearch) extends ArchiveReader {
  import DWArchiveReader._

  protected val ss = new SearchStream(istream,BufferLength)
  protected var res = ss.readResult

  if (!ss.skipTo(headerStart)) throw new RuntimeException(s"not a valid DW archive")

  override def hasMoreData = ss.hasMoreData

  override def readNextEntry: Option[ArchiveEntry] = {
    // we are at a headerStart
    if (ss.readTo(headerEnd,headerStart.length)) {
      val date = readDateTime(res.cs,res.startIdx,res.endIdx)
      if (date != null) {
        if (ss.readTo(headerStart,headerEnd.length)){
          val msg = res.asString(headerEnd.length)
          someEntry(date,msg)
        } else None // did not find next headerStart, nothing we can do

      } else {  // did not find date in header, skip to next chunk
        ss.skipTo(headerStart, headerEnd.length)
        None
      }
    } else None // did not find headerEnd, nothing we can do
  }

  //--- to be provided by concrete subclasses
  protected def readDateTime(cs: Array[Char], startIdx: Int, endIdx: Int) : DateTime

  //--- those can be used by the type specific readDateTime
  protected def readLong(cs: Array[Char], startIdx:Int, endIdx: Int): Long = {
    var c = cs(startIdx)
    var j = startIdx
    var n = 0L
    while (j < endIdx && Character.isDigit(c)){
      n = n * 10 + (c - '0')
      j += 1
      c = cs(j)
    }
    n
  }

  // read all up to the next white space
  protected def readWord(cs: Array[Char], startIdx: Int, endIdx: Int): String = {
    var c = cs(startIdx)
    var j = startIdx
    while (j < endIdx && !Character.isWhitespace(c)){
      j += 1
      c = cs(j)
    }
    if (j > startIdx) new String(cs,startIdx,j-startIdx) else ""
  }
}

abstract class FirstEpochDWArchiveReader(istream: InputStream, hdrStart: String)
                                  extends DWArchiveReader(istream, new BMSearch(hdrStart)) {
  // we just use the x_TIMESTAMP, which is an epoch value directly following our headerStart
  override def readDateTime(cs: Array[Char], i0: Int, i1: Int) = {
    tryNull { new DateTime(readLong(cs, i0+hdrStart.length, i1)) }
  }
}

abstract class DateFieldDWArchiveReader (istream: InputStream, hdrStart: String, field: String)
                                  extends DWArchiveReader(istream, new BMSearch(hdrStart)) {
  val fieldPattern = new BMSearch(field)

  override def readDateTime(cs: Array[Char], i0: Int, i1: Int) = {
    tryNull {
      val i = fieldPattern.indexOfFirst(cs, i0 + hdrStart.length, i1)
      if (i >= 0) DateTime.parse(readWord(cs,i+fieldPattern.length,i1)) else null
    }
  }
}

class AsdexDWArchiveReader (is: InputStream) extends FirstEpochDWArchiveReader(is, "<properties> DEX_TIMESTAMP=")
class SfdpsDWArchiveReader (is: InputStream) extends FirstEpochDWArchiveReader(is, "<properties> DEX_TIMESTAMP=")

//...hopefully DW will convert those to DEX_TIMESTAMP in the future
class TaisDWArchiveReader (is: InputStream) extends DateFieldDWArchiveReader(is, "<properties> queueID=", "timestamp=")
class TfmdataDWArchiveReader(is: InputStream) extends DateFieldDWArchiveReader(is, "<properties> PacketCount=", "TimeStamp=")

