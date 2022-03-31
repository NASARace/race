/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.file.{FileSystems, OpenOption}

import gov.nasa.race.common.BufferRecord
import gov.nasa.race.uom.DateTime

import scala.jdk.CollectionConverters._

/**
  * an ArchiveWriter that writes BufferRecords to a file
  * this is geared towards speed, both for writing and reading (binary data), hence we don't support
  * compression but async IO
  */
abstract class BufferRecordArchiveWriter[R <: BufferRecord](val pathName: String, val openOptions: Set[OpenOption])
                                                 extends ArchiveWriter {
  val recStart = 8 // 8 byte header for archive time stamp

  val rec: R
  val channel = FileChannel.open(FileSystems.getDefault.getPath(pathName),openOptions.asJava)

  protected def allocBuffer(recSize: Int) = {
    ByteBuffer.allocate(recStart + recSize).order(ByteOrder.nativeOrder)
  }

  override def write (date: DateTime, obj: Any): Boolean = {
    if (set(obj)) {
      val buf = rec.buffer
      buf.putLong(0, date.toEpochMillis)
      buf.clear // position=0, limit=capacity
      channel.write(buf)
      true
    } else false
  }

  /**
    * set record field values from suitable obj argument
    * to be provided by concrete type
    */
  protected def set(obj: Any): Boolean

  def close(): Unit = channel.close
}
