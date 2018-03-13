/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.track

import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

import com.typesafe.config.Config
import gov.nasa.race.{Failure, Result, Success}
import gov.nasa.race.common.DenseRecordWriter
import gov.nasa.race.config.ConfigUtils._
import org.joda.time.DateTime

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/**
  * a configurable RecordWriter that uses a mapped byte buffer as store for FloatTrackRecords
  */
class TrackRecordWriter(val config: Config) extends DenseRecordWriter[FloatTrackRecord] {

  val maxRecords: Int = config.getIntOrElse("max-records",5000)
  val pathName = config.getString("pathname")

  val dateOffset: Int = 0
  val recCountOffset: Int = 8
  val headerLength = 12 // 8 bytes for date, 4 bytes for number of set records

  val channel = new RandomAccessFile(pathName, "rw").getChannel
  val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, maxRecords * FloatTrackRecord.size + headerLength)
  val rec = new FloatTrackRecord(buffer,headerLength)

  buffer.order(ByteOrder.nativeOrder)


  override def set(recIndex: Int, msg: Any, isNew: Boolean): Result = {
      msg match {
        case track: TrackedObject =>
          rec.setRecordIndex(recIndex)
          rec.id := track.cs
          rec.date := track.date.getMillis
          rec.lat := track.position.latDeg.toFloat
          rec.lon := track.position.lonDeg.toFloat
          rec.alt := track.altitude.toMeters.toFloat
          rec.hdg := track.heading.toDegrees.toFloat
          rec.spd := track.speed.toMetersPerSecond.toFloat
          rec.stat := track.status

          Success

        case _ => Failure("not a TrackedObject")
      }
  }

  override def updateDate(date: DateTime) = buffer.putLong(dateOffset,date.getMillis)

  override def updateRecCount = buffer.putInt(recCountOffset, recCount)


  override def store: Result = {
    buffer.force
    Success
  }
}
