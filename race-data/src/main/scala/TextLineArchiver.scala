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

import java.io.{InputStreamReader, BufferedReader, InputStream, OutputStream}

import org.joda.time.DateTime

import scala.annotation.tailrec

/**
  * utilities to write and read text line ArchiveEntries, i.e. messages
  * that are delimited by '\n' and hence don't need explicit markers
  */


/**
  * archiver that stores text lines which contain time data, i.e. don't need
  * to explicitly store the message time.
  * Note this always requires a specialized ArchiveReader to replay as the
  * date extraction is message type specific
  */
class TimedTextLineArchiver (val ostream: OutputStream) extends ArchiveWriter {
  override def write(date: DateTime, obj: Any): Boolean = {
    ostream.write(obj.toString.getBytes)
    ostream.write('\n')
    true
  }
}

abstract class TextLineArchiveReader (istream: InputStream) extends ArchiveReader {
  protected val reader = new BufferedReader(new InputStreamReader(istream))
  override def hasMoreData = reader.ready
}

/**
  * archiver that stores the message time as a 16byte msb hex string followed
  * by a ':' followed by a line containing the message text.
  * This can not only be vastly more efficient for ArchiveReader parsing, but
  * also allows to use a generic reader
  */
class HexEpochLineArchiver (val ostream: OutputStream) extends ArchiveWriter {
  val buf = new Array[Byte](17)
  buf(16) = ':' // our separator

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
    longToHexCharBytes(buf, date.getMillis)
    ostream.write(buf)
    ostream.write(obj.toString.getBytes)
    ostream.write('\n')
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
class HexEpochLineArchiveReader (val istream: InputStream) extends TextLineArchiveReader(istream) {

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

  override def read: Option[ArchiveEntry] = {
    try {
      val line = reader.readLine()
      if (line != null) {
        val epochMillis = hexCharsToLong(line)
        Some(ArchiveEntry(new DateTime(epochMillis), line.substring(17)))
      } else None
    } catch {
      case _:Throwable => None
    }
  }
}
