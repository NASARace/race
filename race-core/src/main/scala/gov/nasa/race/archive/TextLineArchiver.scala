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

package gov.nasa.race.archive

import java.io.{BufferedReader, InputStream, InputStreamReader, OutputStream}
import com.typesafe.config.Config
import gov.nasa.race.{Dated, Failure, ResultValue, SuccessValue}
import gov.nasa.race.common.ConfigurableStreamCreator._
import gov.nasa.race.common.{ByteSlice, LineBuffer}
import gov.nasa.race.uom.DateTime

import scala.annotation.tailrec

/*
 * utilities to write and read text line ArchiveEntries, i.e. messages
 * that are delimited by '\n' and hence don't need explicit markers
 */

/**
  * archiver that stores text lines which contain time data, i.e. don't need
  * to explicitly store the message time.
  * Note this always requires a specialized ArchiveReader to replay as the
  * date extraction is message type specific
  */
trait TimedTextLineArchiveWriter extends ArchiveWriter {
  val oStream: OutputStream

  override def write(date: DateTime, obj: Any): Boolean = {
    oStream.write(obj.toString.getBytes)
    oStream.write('\n')
    true
  }
}

// TODO - this should use our own buffering so that we can directly parse byte arrays
trait TextLineArchiveReader extends ArchiveReader {
  val iStream: InputStream

  protected val reader = new BufferedReader(new InputStreamReader(iStream))
  override def hasMoreArchivedData = reader.ready
}

/**
  * an ArchiveReader that reads text lines containing the replay date
  * use this as a base for CSV input
  */
abstract class LineBufferArchiveReader[T<:Dated] (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int) extends ArchiveReader {
  val lineBuffer = new LineBuffer(iStream,bufLen)

  override def hasMoreArchivedData: Boolean = {
    iStream.available > 0
  }

  def initialize(data:ByteSlice): Boolean
  def parseLine(): ResultValue[T]

  def readNextEntry(): Option[ArchiveEntry] = {
    while (true) {
      if (lineBuffer.nextLine()) {
        if (initialize(lineBuffer)) {
          parseLine() match {
            case SuccessValue(o) => return archiveEntry(o.date, o)
            case Failure(msg) => // line did not parse. try next TODO - report error
          }
        }
      } else return None // no more lines
    }
    None
  }

  override def close(): Unit = {
    iStream.close()
    lineBuffer.close()
  }
}

/**
  * archiver that stores the message time as a 16byte msb hex string followed
  * by a ':' followed by a line containing the message text.
  * This can not only be vastly more efficient for ArchiveReader parsing, but
  * also allows to use a generic reader
  */
class HexEpochLineArchiveWriter(val oStream: OutputStream, val pathName: String = "<unknown>") extends ArchiveWriter {

  def this (conf: Config) = this(createOutputStream(conf),configuredPathName(conf))

  val buf = new Array[Byte](17)
  buf(16) = ':' // our separator

  def close(): Unit = oStream.close

  def longToHexCharBytes(buf: Array[Byte], v: Long) = {
    @tailrec def _setHexChar (a: Array[Byte], i: Int, v: Long): Unit = {
      if (i < 16) {
        val b = (v & 0xf).toByte
        val c: Byte = if (b < 10) ('0' + b).toByte else ('a' + (b - 10)).toByte
        a(15-i) = c
        _setHexChar(a,i+1,v>>4)
      }
    }
    _setHexChar(buf,0,v)
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    longToHexCharBytes(buf, date.toEpochMillis)
    oStream.write(buf)
    oStream.write(obj.toString.getBytes)
    oStream.write('\n')
    true
  }
}

/**
  * class to read ArchiveEntry lines written by HexEpochLineArchiver, which stores
  * each message in a '\n' delimited line, starting with 16 hex chars encoding the
  * message time as a long (millis since epoch), followed by ':', followed by the
  * message payload.
  * This is more efficient than having to parse complex date formats
  *
  * <2do> using a BufferedReader is probably not the most efficient approach since
  * it involves turning bytes (istream) into a char array (buffer), into a StringBuffer
  * (readLine), into a String (line), into a substring (msg), and back into a char array
  * if the message has to be parsed as text. We should have a LineReader that returns a
  * Array[Char] to avoid all the temporary objects
  */
class HexEpochLineArchiveReader (val iStream: InputStream, val pathName: String = "<unknown>") extends TextLineArchiveReader {

  def this (conf: Config) = this(createInputStream(conf),configuredPathName(conf))

  override def close(): Unit = iStream.close

  final def hexCharsToLong(line: String): Long = {
    @tailrec def _readHexChar (s: String, i: Int, acc: Long): Long = {
      if (i < 16) {
        val c = s.charAt(i)
        val l = if (c < 'a') (c.toLong - '0') else (10 + c.toLong - 'a')
        _readHexChar(s, i+1, (acc << 4) + l)
      } else acc
    }
    _readHexChar(line,0,0)
  }

  override def readNextEntry(): Option[ArchiveEntry] = {
    try {
      val line = reader.readLine()
      if (line != null) archiveEntry(DateTime.ofEpochMillis(hexCharsToLong(line)), line.substring(17)) else None
    } catch {
      case _:Throwable => None
    }
  }
}
