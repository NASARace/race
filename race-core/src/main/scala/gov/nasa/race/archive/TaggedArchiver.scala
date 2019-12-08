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
package gov.nasa.race.archive

import java.io.OutputStream

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createOutputStream}
import gov.nasa.race.common.{ASCIIBuffer, StringDataBuffer, UTF8Buffer}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.util.NumUtils

object TaggedArchiver {
  final val SOH: Byte = '\f' // start of header
  final val EOH: Byte = '\n' // end of header
  final val FS: Byte  = ',' // field separator
  final val LF: Byte = '\n' // line feed
}
import gov.nasa.race.archive.TaggedArchiver._

/**
  * an ArchiveWriter that stores entry data as UTF-8 strings which are tagged by fixed size entry headers
  *
  * each entry is preceded by a entry header of the format
  *   LF           : line feed char
  *   SOH          : start of header char
  *   8 hex chars  : offset to refDate in milliseconds, encoded as 0-padded hex string
  *   FS           : field separator char
  *   8 hex chars  : length of entry data in bytes (only payload, no header)
  *   EOH          : end of header char
  *
  * the file header uses the following format:
  *   16 hex chars : refDate in epoch milliseconds, encoded as 0-padded hex string
  *   EOH          : end of header char
  *
  * note that we cannot easily rewrite content upon close (e.g. to add the number of written entries) since
  * the oStream might be compressed, i.e. the file would have to be completely rewritten
  */
trait TaggedTextArchiveWriter extends ArchiveWriter {
  val oStream: OutputStream
  val pathName: String
  val buf: StringDataBuffer

  val hexBuf = new Array[Byte](16)

  val fileHeaderLength = 27 // SOH + hex16 + FS + hex8 + EOH
  val nEntriesOffset = 18
  val entryHeaderLength = 20 // LF + SOH + hex8 + FS + hex8 + EOH

  var nEntries = 0
  var refDate: DateTime = UndefinedDateTime
  var entryBytes: Array[Byte] = null
  var entrySize: Int = 0

  @inline final def writeHex8 (n: Int): Unit = {
    NumUtils.intToHexPadded(n, hexBuf,8)
    oStream.write(hexBuf,0,8)
  }

  @inline final def writeHex16 (n: Long): Unit = {
    NumUtils.longToHexPadded(n, hexBuf,16)
    oStream.write(hexBuf,0,16)
  }

  // override in subclasses for specific conversions of entry data
  protected def toString(obj: Any) = obj.toString

  protected def writeEntryHeader(date: DateTime, length: Int): Unit = {
    oStream.write(LF)
    oStream.write(SOH)
    writeHex8(date.timeSince(refDate).toMillis)
    oStream.write(FS)
    writeHex8(length)
    oStream.write(EOH)
  }

  protected def setEntryBytes(obj: Any): Unit = {
    @inline def fromString(s: String): Unit = {
      buf.encode(s)
      entryBytes = buf.data
      entrySize = buf.length
    }

    obj match {
      case a:Array[Byte] => // no need to copy - directly use object
        entryBytes = a
        entrySize = a.length

      case s:String => fromString(s)
      case x => fromString(x.toString)
    }
  }

  protected def writeFileHeader: Unit = {
    writeHex16(refDate.toEpochMillis)
    oStream.write(EOH)
  }

  //--- ArchiveWriter interface

  override def open (date: DateTime): Unit = {
    refDate = date
    writeFileHeader
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    setEntryBytes(obj)
    writeEntryHeader(date,entrySize)
    oStream.write(entryBytes,0,entrySize)
    nEntries += 1
    true
  }

  override def close: Unit = {
    oStream.close
  }
}

/**
  * a TaggedTextArchiveWriter that can store full unicode strings
  */
class TaggedStringArchiveWriter (val oStream: OutputStream, val pathName:String="<unknown>") extends TaggedTextArchiveWriter {
  val buf = new UTF8Buffer

  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))
}

/**
  * a TaggedTextArchiveWriter that only stores ASCII strings
  */
class TaggedASCIIArchiveWriter (val oStream: OutputStream, val pathName:String="<unknown>") extends TaggedTextArchiveWriter {
  val buf = new ASCIIBuffer

  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))
}