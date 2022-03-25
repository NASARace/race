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
package gov.nasa.race.track

import java.nio.file.OpenOption

import com.typesafe.config.Config
import gov.nasa.race.archive.BufferRecordArchiveWriter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.FileUtils

object TrackRecordArchiver {
  def getOptions(config: Config): Set[OpenOption] = {
    FileUtils.getOpenOptionsOrElse(config.getStringArray("options"), FileUtils.DefaultOpenWrite)
  }
  def getPathName(config: Config): String = config.getString("pathname")
}
import TrackRecordArchiver._

/**
  * a BufferRecordArchiveWriter for TrackRecords
  */
class TrackRecordArchiveWriter(pathName: String, openOptions: Set[OpenOption])
                    extends BufferRecordArchiveWriter[TrackRecord](pathName,openOptions) {

  def this(config: Config) = this(getPathName(config),getOptions(config))

  override val rec = TrackRecord(recStart, 1)

  override protected def set(obj: Any): Boolean = {
    obj match {
      case track: Tracked3dObject =>
        rec.id := track.id
        rec.cs := track.cs
        rec.date := track.date.toEpochMillis
        rec.stat := track.status

        val pos = track.position
        rec.lat := pos.latDeg
        rec.lon := pos.lonDeg
        rec.alt := pos.altMeters

        rec.hdg := track.heading.toDegrees
        rec.spd := track.speed.toMetersPerSecond
        rec.vr  := track.vr.toMetersPerSecond

        true

      case _ => false // don't know how to set record from argument
    }
  }
}

class FloatTrackRecordArchiveWriter(pathName: String, openOptions: Set[OpenOption])
                       extends BufferRecordArchiveWriter[FloatTrackRecord](pathName,openOptions) {

  def this(config: Config) = this(getPathName(config),getOptions(config))

  override val rec = FloatTrackRecord(recStart, 1)

  override protected def set(obj: Any): Boolean = {
    obj match {
      case track: Tracked3dObject =>
        rec.id := track.id
        rec.cs := track.cs
        rec.date := track.date.toEpochMillis
        rec.stat := track.status

        val pos = track.position
        rec.lat := pos.latDeg.toFloat
        rec.lon := pos.lonDeg.toFloat
        rec.alt := pos.altMeters.toFloat

        rec.hdg := track.heading.toDegrees.toFloat
        rec.spd := track.speed.toMetersPerSecond.toFloat
        rec.vr  := track.vr.toMetersPerSecond.toFloat

        true

      case _ => false // don't know how to set record from argument
    }
  }
}