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
import java.nio.channels.FileChannel

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.common.RecordWriter

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/**
  * a configurable RecordWriter that uses a mapped byte buffer as store for FloatTrackRecords
  */
class MappedTrackRecordWriter (val config: Config) extends RecordWriter {

  val maxRecords: Int = config.getIntOrElse("max-records",5000)
  val pathName = config.getString("pathname")
  val headerLength = 12 // 8 bytes for date, 4 bytes for number of set records

  val channel = new RandomAccessFile(pathName, "rw").getChannel
  val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, maxRecords * FloatTrackRecord.size + headerLength)
  val rec = new FloatTrackRecord(buffer,headerLength)

  @tailrec
  final def tryLockedFor (nTimes: Int, delay: FiniteDuration)(action: =>Unit): Boolean = {
    val lock = channel.tryLock
    if (lock != null) {
      action
      lock.release
      true

    } else {
      if (nTimes > 0) {
        Thread.sleep(delay.toMillis)  // not very actor idiomatic but still less overhead than resend or longer busy wait
        tryLockedFor(nTimes-1, delay)(action)
      } else {
        false
      }
    }
  }

  override def set(recIndex: Int, o: Any): Boolean = {
    o match {
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
        true
      case _ => false
    }
  }

  override def move(fromIndex: Int, toIndex: Int): Boolean = {
    rec(fromIndex).moveTo(toIndex)
    true
  }

  override def remove(recIndex: Int): Boolean = {
    rec(recIndex).clear
    true
  }

  override def store: Boolean = {
    buffer.force
    true
  }
}
