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
import gov.nasa.race.common.DenseRecordWriter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.{Failure, Result, Success}
import gov.nasa.race.uom.DateTime


/**
  * a configurable RecordWriter that uses a mapped byte buffer as store for FloatTrackRecords
  */
class TrackRecordWriter(val config: Config) extends DenseRecordWriter[FloatTrackRecord] {

  val maxRecords: Int = config.getIntOrElse("max-records",5000)
  val pathName = config.getString("pathname")

  val storeSizeOffset: Int = 0  // 2GB should be enough (>30M double records)
  val recCountOffset: Int = 4
  val dateOffset: Int = 8
  val headerLength = 16 // 8 bytes for date, 4 bytes for number of set records, 4 bytes for store size

  val channel = new RandomAccessFile(pathName, "rw").getChannel
  val bufferSize = maxRecords * FloatTrackRecord.size + headerLength
  val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bufferSize)
  val rec = new FloatTrackRecord(buffer,headerLength)

  buffer.order(ByteOrder.nativeOrder)
  buffer.putInt(storeSizeOffset,bufferSize) // this is static

  override def set(recIndex: Int, msg: Any, isNew: Boolean): Result = {
      msg match {
        case track: Tracked3dObject =>
          rec.setRecordIndex(recIndex)
          rec.id := track.id
          rec.cs := track.cs
          rec.date := track.date.toEpochMillis

          val pos = track.position
          rec.lat := pos.latDeg.toFloat
          rec.lon := pos.lonDeg.toFloat
          rec.alt := pos.altMeters.toFloat

          rec.hdg := track.heading.toDegrees.toFloat
          rec.spd := track.speed.toMetersPerSecond.toFloat
          rec.vr := track.vr.toMetersPerSecond.toFloat
          rec.stat := track.status

          Success

        case _ => Failure("not a TrackedObject")
      }
  }

  override def updateDate(date: DateTime) = buffer.putLong(dateOffset,date.toEpochMillis)

  override def updateRecCount = buffer.putInt(recCountOffset, recCount)


  override def store: Result = {
    buffer.force
    Success
  }
}
