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

import com.github.nscala_time.time.Imports._
import gov.nasa.race.common.{DateAdjuster, Dated}
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

case class ArchiveEntry (date: DateTime, obj: Any) extends Dated

trait ArchiveWriter {
  val ostream: OutputStream

  def close = ostream.close
  def write (date: DateTime, obj: Any): Boolean
}

trait ArchiveReader extends DateAdjuster {
  val istream: InputStream

  def close = istream.close
  def hasMoreData = istream.available() > 0

  def read: Option[ArchiveEntry]
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