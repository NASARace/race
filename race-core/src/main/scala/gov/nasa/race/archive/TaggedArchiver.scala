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

import java.io.{InputStream, OutputStream}
import java.util

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.common.ConfigurableStreamCreator._
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.common.{ASCIIBuffer, StringDataBuffer, UTF8Buffer}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Time._
import gov.nasa.race.util.NumUtils

/**
  * archive/replay for tagged text archives comprised of fixed file/entry headers and variable length
  * text payloads of entities.
  *
  * Note that the tagged text archive format is agnostic with respect to payload formats, which are only
  * utf-8 byte array (slices).
  *
  * The primary goal of the tagged text archive format is to support 0-copy reads of compressed archives with
  * entry meta info such as replay date.
  *
  * the file header uses the following format:
  *   16 hex chars : refDate in epoch milliseconds, encoded as 0-padded hex string
  *   EOH          : end of header char
  *
  * each entry is preceded by a entry header of the format
  *   LF           : line feed char
  *   SOH          : start of header char
  *   8 hex chars  : offset to refDate in milliseconds, encoded as 0-padded hex string
  *   FS           : field separator char
  *   8 hex chars  : length of entry data in bytes (only payload, no header)
  *   EOH          : end of header char
  */
object TaggedArchiver {
  final val SOH: Byte = '\f' // start of header
  final val EOH: Byte = '\n' // end of header
  final val FS: Byte  = ',' // field separator
  final val LF: Byte = '\n' // line feed

  final val fileHeaderLength = 27 // SOH + hex16 + FS + hex8 + EOH
  final val nEntriesOffset = 18
  final val entryHeaderLength = 20 // LF + SOH + hex8 + FS + hex8 + EOH
}
import gov.nasa.race.archive.TaggedArchiver._

/**
  * an ArchiveWriter that that uses the tagged archive format
  */
trait TaggedTextArchiveWriter extends ArchiveWriter {
  val oStream: OutputStream
  val pathName: String
  val buf: StringDataBuffer

  val hexBuf = new Array[Byte](16)

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
class TaggedStringArchiveWriter (val oStream: OutputStream, val pathName:String="<unknown>", initBufferSize: Int) extends TaggedTextArchiveWriter {
  val buf = new UTF8Buffer(initBufferSize)

  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))
}

/**
  * a TaggedTextArchiveWriter that only stores ASCII strings
  */
class TaggedASCIIArchiveWriter (val oStream: OutputStream, val pathName:String="<unknown>", initBufferSize: Int) extends TaggedTextArchiveWriter {
  val buf = new ASCIIBuffer(initBufferSize)

  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))
}

/**
  * an ArchiveReader that uses the tagged archive format
  *
  * note that subclasses can include payload translation to avoid creating raw input copies
  */
trait TaggedTextArchiveReader extends ArchiveReader {
  val iStream: InputStream
  val pathName: String
  val initBufferSize: Int

  protected var buf: Array[Byte] = new Array[Byte](initBufferSize)
  protected val slice: Slice = Slice(buf,0,0)

  protected var refDate: DateTime = UndefinedDateTime

  if (!readFileHeader) throw new RuntimeException(s"could not read tagged archive file header of $pathName")

  protected def growBuffer (newSize: Int): Unit = {
    buf = new Array[Byte](newSize)
    slice.set(buf,0,0)
  }

  protected def readFileHeader: Boolean = {
    if (iStream.read(buf,0,fileHeaderLength) == fileHeaderLength){
      slice.setRange(0,16)
      refDate = DateTime.ofEpochMillis(slice.toHexLong)
      true
    } else false
  }

  /**
    * turn contents of buf up to limit into entry data object that can be stored/processed by clients
    * override if concrete type does translation
    */
  protected def entryData (limit: Int): Any

  protected def readEntry: Boolean = {
    if (iStream.read(buf,0,entryHeaderLength) == entryHeaderLength) {
      slice.setRange(2, 8)
      val entryDate = refDate + Milliseconds(slice.toHexInt)
      slice.setRange(10, 8)
      val entryLength = slice.toHexInt

      if (entryLength > 0) {
        if (entryLength > buf.length) growBuffer(entryLength)
        if (iStream.read(buf, 0, entryLength) == entryLength) {
          nextEntry.date = entryDate
          nextEntry.msg = entryData(entryLength)
          true
        } else false // incomplete entry data
      } else false // empty entry
    } else false // no or incomplete entry header
  }

  //--- ArchiveReader interface

  override def hasMoreData: Boolean = iStream.available > 0

  override def close: Unit = iStream.close

  override def readNextEntry: Option[ArchiveEntry] = {
    if (readEntry) {
      someEntry
    } else None
  }

  override def setRefDate (date: DateTime): Unit = refDate = date
}

class TaggedStringArchiveReader (val iStream: InputStream, val pathName:String="<unknown>",
                                 val initBufferSize: Int = 8192, val publishRaw: Boolean = false) extends TaggedTextArchiveReader {

  def this (conf: Config) = this(createInputStream(conf), configuredPathName(conf),
                                 conf.getIntOrElse("buffer-size",4096), conf.getBooleanOrElse("publish-raw", false))

  override protected def entryData (limit: Int): Any = {
    if (publishRaw) util.Arrays.copyOf(buf,limit) else new String(buf,0,limit)
  }
}
