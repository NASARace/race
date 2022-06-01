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
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
import gov.nasa.race.common.{BMSearch, SearchStream}
import gov.nasa.race.uom.DateTime

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
abstract class DWArchiveReader (iStream: InputStream, val headerStart: BMSearch) extends ArchiveReader {
  import DWArchiveReader._

  protected val ss = new SearchStream(iStream,BufferLength)
  protected var res = ss.readResult

  if (!ss.skipTo(headerStart)) throw new RuntimeException(s"not a valid DW archive")

  override def hasMoreArchivedData = ss.hasMoreData
  override def close(): Unit = ss.close

  override def readNextEntry(): Option[ArchiveEntry] = {
    // we are at a headerStart
    if (ss.readTo(headerEnd,headerStart.patternLength)) {
      val date = readDateTime(res.cs,res.startIdx,res.endIdx)
      if (date.isDefined) {
        if (ss.readTo(headerStart,headerEnd.patternLength)){
          val msg = res.asString(headerEnd.patternLength)
          archiveEntry(date,msg)
        } else None // did not find next headerStart, nothing we can do

      } else {  // did not find date in header, skip to next chunk
        ss.skipTo(headerStart, headerEnd.patternLength)
        None
      }
    } else None // did not find headerEnd, nothing we can do
  }

  //--- to be provided by concrete subclasses
  protected def readDateTime(cs: Array[Byte], startIdx: Int, endIdx: Int) : DateTime

  //--- those can be used by the type specific readDateTime
  protected def readLong(bs: Array[Byte], startIdx:Int, endIdx: Int): Long = {
    var c = bs(startIdx)
    var j = startIdx
    var n = 0L
    while (j < endIdx && Character.isDigit(c)){
      n = n * 10 + (c - '0')
      j += 1
      c = bs(j)
    }
    n
  }

  // read all up to the next white space
  protected def readWord(bs: Array[Byte], startIdx: Int, endIdx: Int): String = {
    var c = bs(startIdx)
    var j = startIdx
    while (j < endIdx && !Character.isWhitespace(c)){
      j += 1
      c = bs(j)
    }
    if (j > startIdx) new String(bs,startIdx,j-startIdx) else ""
  }
}

/**
  * DW archive with msg time that is first text field following '<properties>' header
  * field value is epoch in millis
  * NOTE - this is fragile since DW header formats change
  */
abstract class FirstEpochDWArchiveReader(iStream: InputStream, hdrStart: String)
                                       extends DWArchiveReader(iStream, new BMSearch(hdrStart)) {
  // we just use the x_TIMESTAMP, which is an epoch value directly following our headerStart
  override def readDateTime(cs: Array[Byte], i0: Int, i1: Int): DateTime = {
    DateTime.ofEpochMillis(readLong(cs, i0+hdrStart.length, i1))
  }
}

/**
  * DW archive with msg time that is some text field within '<properties>' header
  * field value is epoch in millis
  * note - this is not as fragile as FirstEpochDWArchiveReader but less efficient for direct replay since it requires two searches
  */
abstract class EpochFieldDWArchiveReader (iStream: InputStream, hdrStart: String, field: String)
                                          extends DWArchiveReader(iStream, new BMSearch(hdrStart)) {
  val fieldPattern = new BMSearch(field)

  override def readDateTime(bs: Array[Byte], i0: Int, i1: Int) = {
    val i = fieldPattern.indexOfFirstInRange(bs, i0 + hdrStart.length, i1)
    if (i >= 0) {
      val millis = readLong(bs, i + field.length, i1)
      if (i0 < 300) println(s"$millis")
      DateTime.ofEpochMillis(millis)
    } else DateTime.UndefinedDateTime
  }
}

/**
  * DW archive with message time in some text field within '<properties>' header
  * field value is YMDT string
  */
abstract class DateFieldDWArchiveReader (iStream: InputStream, hdrStart: String, field: String)
                                        extends DWArchiveReader(iStream, new BMSearch(hdrStart)) {
  val fieldPattern = new BMSearch(field)

  override def readDateTime(bs: Array[Byte], i0: Int, i1: Int) = {
      val i = fieldPattern.indexOfFirstInRange(bs, i0 + hdrStart.length, i1)
      if (i >= 0) DateTime.parseYMDT(readWord(bs,i+fieldPattern.patternLength,i1)) else DateTime.UndefinedDateTime
  }
}


import gov.nasa.race.common.ConfigurableStreamCreator._

// the DW <properties> headers are a ever changing mess. It would be nice if all would start with a DEX_TIMESTAMP epoch
// which would kill two birds with one stone - disambiguate the element and avoid having to parse its content for
// a suitable message injection time stamp

// unfortunately not all airports have a DEX_TIMESTAMP so we still have to use the old 'timestamp' parser.
// At least that also covers old archives
class AsdexDWArchiveReader (val iStream: InputStream, val pathName: String="<unknown>")
                                           extends DateFieldDWArchiveReader(iStream, "<properties> ", "timestamp=") {
  def this(conf: Config) = this(createInputStream(conf),configuredPathName(conf))
}

// SFDPS also has FDPSMsg messages that have payload properties elements
class SfdpsDWArchiveReader (val iStream: InputStream, val pathName: String="<unknown>")
                                           extends DateFieldDWArchiveReader(iStream, "<properties>", "FDPS_SentTime=") {
  def this(conf: Config) = this(createInputStream(conf),configuredPathName(conf))
}

// no DEX_TIMESTAMP yet for TAIS and TFMData

class TaisDWArchiveReader (val iStream: InputStream, val pathName: String="<unknown>")
                                           extends DateFieldDWArchiveReader(iStream, "<properties> queueID=", "timestamp=") {
  def this(conf: Config) = this(createInputStream(conf),configuredPathName(conf))
}

class TfmdataDWArchiveReader(val iStream: InputStream, val pathName: String="<unknown>")
                                           extends DateFieldDWArchiveReader(iStream, "<properties> PacketCount=", "TimeStamp=") {
  def this(conf: Config) = this(createInputStream(conf),configuredPathName(conf))
}

