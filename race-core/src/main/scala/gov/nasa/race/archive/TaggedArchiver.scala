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
import gov.nasa.race.common.ConfigurableStreamCreator._
import gov.nasa.race.common.{AsciiBuffer, ByteSlice, MutAsciiSlice, MutCharSeqByteSlice, MutRawByteSlice, Parser, StringDataBuffer, Utf8Buffer}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Time._
import gov.nasa.race.util.{ArrayUtils, NumUtils}

import scala.collection.Seq

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
  *   FS           : field separator char
  *   8 hex chars  : byte length of extra header data (following file header, preceding first entry)
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
  final val LF: Byte  = '\n' // line feed

  final val fileHeaderLength  = 26 // hex16 + FS + hex8 + EOH
  final val entryHeaderLength = 20 // LF + SOH + hex8 + FS + hex8 + EOH
}
import gov.nasa.race.archive.TaggedArchiver._

/**
  * an ArchiveWriter that that uses the tagged archive format
  */
trait TaggedArchiveWriter extends ArchiveWriter {
  val oStream: OutputStream
  val pathName: String

  val hexBuf = new Array[Byte](16)

  var nEntries = 0
  var refDate: DateTime = UndefinedDateTime

  val entryData: MutRawByteSlice = MutRawByteSlice.empty

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

  def writeEntryHeader(date: DateTime, length: Int): Unit = {
    oStream.write(LF)
    oStream.write(SOH)
    writeHex8(date.timeSince(refDate).toMillis.toInt)  // TODO - change to Hex16 (but don't break existing archives)
    oStream.write(FS)
    writeHex8(length)
    oStream.write(EOH)
  }

  protected def setEntryBytes(obj: Any): Unit

  def writeFileHeader (extraHeaderData: Array[Byte], len: Int): Unit = {
    writeHex16(refDate.toEpochMillis)
    oStream.write(FS)
    writeHex8(len)
    oStream.write(EOH)

    oStream.write(extraHeaderData,0,len)
  }

  def writeFileHeader (extraHeaderData: String): Unit = {
    val a = extraHeaderData.getBytes
    writeFileHeader(a,a.length)
  }

  //--- ArchiveWriter interface

  override def open (date: DateTime, extraHeaderData: String): Unit = {
    refDate = date
    writeFileHeader(extraHeaderData)
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    setEntryBytes(obj)
    writeEntryHeader(date,entryData.len)
    oStream.write(entryData.data, entryData.off, entryData.len)
    nEntries += 1
    true
  }

  override def close(): Unit = {
    oStream.close
  }
}

trait TaggedTextArchiveWriter extends TaggedArchiveWriter {
  lazy val sdb = createStringDataBuffer
  protected def createStringDataBuffer: StringDataBuffer

  override protected def setEntryBytes(obj: Any): Unit = {
    obj match {
      case s: String =>
        sdb.encode(s)
        entryData.setFrom(sdb)
      case a: Array[Byte] =>
        entryData.set(a, 0, a.length)
      case slice: ByteSlice =>
        entryData.setFrom(slice)
      case _ =>
        entryData.clear()
    }
  }
}

/**
  * a TaggedTextArchiveWriter that can store full unicode strings
  */
class TaggedStringArchiveWriter (val oStream: OutputStream, val pathName:String="<unknown>", initBufferSize: Int = 8192) extends TaggedTextArchiveWriter {
  override protected def createStringDataBuffer = new Utf8Buffer(initBufferSize)

  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))
}

/**
  * a TaggedTextArchiveWriter that only stores ASCII strings
  */
class TaggedASCIIArchiveWriter (val oStream: OutputStream, val pathName:String="<unknown>", initBufferSize: Int) extends TaggedTextArchiveWriter {
  override protected def createStringDataBuffer = new AsciiBuffer(initBufferSize)

  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))
}

/**
  * an ArchiveReader that uses the tagged archive format
  *
  * note that subclasses can include payload translation to avoid creating raw input copies
  *
  * note also that subclasses can call "if (initialized).." at the end of their ctor to make
  * sure the input stream is readable - otherwise this will be deferred to the first readNextEntry call
  */
trait TaggedArchiveReader extends ArchiveReader {
  val iStream: InputStream
  val pathName: String
  val initBufferSize: Int

  lazy val initialized: Boolean = initialize

  // set by initialize
  protected var buf: Array[Byte] = _
  protected var slice: MutCharSeqByteSlice = _

  protected var refDate: DateTime = UndefinedDateTime
  protected var extraFileHeader: Array[Byte] = Array.empty[Byte]

  protected var entryDate: DateTime = UndefinedDateTime
  protected var entryDateOffset: Time = UndefinedTime
  protected var entryLength: Int = -1

  def getRefDate: DateTime = refDate
  def getExtraFileHeader: String = new String(extraFileHeader)

  // we can't call this from our own init since pathname/istream/initBufferSize are set by derived type
  private def initialize: Boolean = {
    buf = new Array[Byte](initBufferSize)
    slice = new MutAsciiSlice(buf,0,0)
    readFileHeader
  }

  protected def growBuffer (newSize: Int): Unit = {
    buf = new Array[Byte](newSize)
    slice.set(buf,0,0)
  }

  protected def readFileHeader: Boolean = {
    if (iStream.read(buf,0,fileHeaderLength) == fileHeaderLength){
      slice.setRange(0,16)
      refDate = DateTime.ofEpochMillis(slice.toHexLong)
      slice.setRange(17,8)
      val extraHdrLength = slice.toHexInt
      if (extraHdrLength > 0) {
        val extraData = new Array[Byte](extraHdrLength)
        if (iStream.read(extraData, 0, extraData.length) == extraHdrLength) {
          extraFileHeader = extraData
          true
        } else false // inconsistent archive, failed to read extra header data
      } else true // no extra data
    } else false // incomplete file header
  }

  /**
    * turn contents of buf up to limit into entry data object that can be stored/processed by clients
    * override if concrete type does translation
    * if this method returns null, None or an empty Seq we loop
    */
  protected def parseEntryData(limit: Int): Any

  protected def readEntry: Boolean = {
    while (true) {
      if (iStream.read(buf, 0, entryHeaderLength) == entryHeaderLength) {
        slice.setRange(2, 8)
        entryDateOffset = Milliseconds(slice.toHexInt)
        entryDate = refDate + entryDateOffset
        slice.setRange(11, 8)
        entryLength = slice.toHexInt
        if (entryLength > 0) {
          if (entryLength > buf.length) growBuffer(entryLength)
          if (iStream.read(buf, 0, entryLength) == entryLength) {
            nextEntry.date = entryDate
            nextEntry.msg = parseEntryData(entryLength) match {
              case null => null
              case None => null
              case Some(obj) => obj
              case list:Seq[_] if list.isEmpty => null
              case obj => obj
            }
            if (nextEntry.msg != null) return true

          } else return false // not enough entry data
        } else return false // empty entry
      } else return false // no or incomplete entry header
    }
    throw new RuntimeException("should not get here")
  }

  //--- low level read functions (note these are actually less efficient)

  // this positions the stream *after* the next opening LF SOH marker (if any is left)
  // buf will contain the data from where we start to read up to the opening LF
  // the data length in buf is returned
  protected def readToNextEntryHeaderStart(): Int = {
    var nRead = 0

    @inline def push (c: Int): Unit = {
      if (nRead >= buf.length) {
        buf = ArrayUtils.grow(buf,buf.length * 2)
        slice.set(buf,0,0)
      }
      buf(nRead) = c.toByte
      nRead += 1
    }

    var c: Int = iStream.read()
    while (c != -1) {
      if (c == LF) {
        c = iStream.read()
        if (c == SOH) {
          return nRead
        } else {
          push(LF)
        }

      } else {
        push(c)
        c = iStream.read()
      }
    }
    nRead
  }

  // the stream has to be positioned after the LF SOH marker
  protected def readEntryHeaderFields(): Boolean = {
    if (iStream.read(buf, 0, entryHeaderLength - 2) == entryHeaderLength - 2){
      slice.setRange(0, 8)
      entryDateOffset = Milliseconds(slice.toHexInt)
      entryDate = refDate + entryDateOffset
      slice.setRange(9, 8)
      entryLength = slice.toHexInt
      true
    } else false
  }


  //--- ArchiveReader interface

  override def hasMoreArchivedData: Boolean = iStream.available > 0

  override def close(): Unit = iStream.close

  override def readNextEntry(): Option[ArchiveEntry] = {
    if (initialized) {
      if (readEntry) someEntry else None
    } else None
  }

  override def setRefDate (date: DateTime): Unit = refDate = date
}

class TaggedStringArchiveReader (val iStream: InputStream, val pathName:String="<unknown>",
                                 val initBufferSize: Int = 8192, val publishRaw: Boolean = false) extends TaggedArchiveReader {

  def this (conf: Config) = this(createInputStream(conf), configuredPathName(conf),
                                 conf.getIntOrElse("buffer-size",4096), conf.getBooleanOrElse("publish-raw", false))

  override protected def parseEntryData(limit: Int): Any = {
    if (publishRaw) util.Arrays.copyOf(buf,limit) else new String(buf,0,limit)
  }
}

abstract class ConfiguredTAReader (conf: Config) extends TaggedArchiveReader {
  override val iStream = createInputStream(conf)
  override val pathName = configuredPathName(conf)
  override val initBufferSize = conf.getIntOrElse("buffer-size", 32768)

  if (!initialized) throw new RuntimeException(s"failed to initialize tagged archive $pathName")
}


class ParsingArchiveReader  (val parser: Parser,
                             val iStream: InputStream,
                             val pathName: String="<unknown>",
                             val initBufferSize: Int = 32768) extends TaggedArchiveReader {
  def this(parser:Parser, conf: Config) = this(
    parser,
    createInputStream(conf),
    configuredPathName(conf),
    conf.getIntOrElse("buffer-size", 32768)
  )

  override protected def parseEntryData(limit: Int): Any = {
    // this should return a fully initialized parser if this is a message of relevant type
    parser.parse(buf, 0, limit)
  }
}
