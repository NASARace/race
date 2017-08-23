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

import java.io._

import gov.nasa.race.Dated
import org.joda.time.DateTime

/**
 * support for archiving and replay of data
 *
 * unfortunately we can't do this typed since the Archive/ReplayActors are untyped
 * (instantiates ArchiveWriter/Reader via reflection) and its message handlers
 * would need the types. Besides, the archiver might actually accept different input formats
 * (text, objects). This pushed type checks into the archiver implemtation
 *
 * note that we close the streams from here since esp. the concrete ArchiveWriters
 * might use additional decorators such as BufferedOutputStream and have to make sure
 * these are properly flushed
 */


trait ArchiveWriter {
  val ostream: OutputStream

  def close = {
    ostream.flush
    ostream.close
  }
  def write (date: DateTime, obj: Any): Boolean
}

trait ArchiveReader extends DateAdjuster {
  class ArchiveEntry (var date: DateTime, var msg: Any) extends Dated

  protected val nextEntry = new ArchiveEntry(null,null)
  protected val someEntry = Some(nextEntry) // avoid gazillions of short living options

  protected def someEntry(d: DateTime, m: Any): Option[ArchiveEntry] = {
    nextEntry.date = d
    nextEntry.msg = m
    someEntry
  }

  // to be provided by subtypes
  def close: Unit
  def hasMoreData: Boolean
  def readNextEntry: Option[ArchiveEntry]
}

/**
  * a InputStream based ArchiveReader
  */
trait StreamArchiveReader extends ArchiveReader {
  protected val istream: InputStream

  override def close = istream.close
  override def hasMoreData = istream.available() > 0 // override if the reader does its own buffering
}

class DummyReader extends ArchiveReader {
  override def close = {}
  override def hasMoreData = false
  override def readNextEntry = None
}

/**
  * archiver to be used for messages that don't need boundary markers and
  * carry their own time stamps, i.e. don't need to record the message time separately.
  *
  * Note this always requires a specialized ArchiveReader to replay, since extracting
  * the date is message type specific
  */
class RawTimedMessageArchiver (val ostream: OutputStream) extends ArchiveWriter {
  override def write(date: DateTime, obj: Any): Boolean = {
    ostream.write(obj.toString.getBytes)
    true
  }
}