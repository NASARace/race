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
package gov.nasa.race.air.actor

import java.io.InputStream

import com.typesafe.config.Config
import gov.nasa.race.actor.ReplayActor
import gov.nasa.race.air.{FlightCompleted, FlightPos}
import gov.nasa.race.archive.{ArchiveReader, StreamArchiveReader}
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.avro.TrackPoint
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import org.apache.avro.file.DataFileStream
import org.apache.avro.specific.SpecificDatumReader
import org.joda.time.DateTime

/**
  * a ReplayActor that reads Avro TrackPoint archives and emits them as FlightMessage objects
  *
  * we use a specialized StreamArchiveReader here because that way we also support compressed
  * Avro archives (up to 30% compression for TrackPoint archives)
  *
  * TODO - we might extend FlightPos with a 'completed' state, in which case the 'completed' cache can be dropped
  */
class TrackPointReplayActor (config: Config) extends ReplayActor(config) {

  class TPReader (val istream: InputStream) extends StreamArchiveReader {
    val dfr = new DataFileStream(istream, new SpecificDatumReader[TrackPoint])
    val recCache = new TrackPoint
    var pendingComplete: FlightCompleted = null // to be set if we encounter a completed TrackPoint

    override def hasMoreData = (pendingComplete != null) || dfr.hasNext
    override def close = dfr.close

    override def readNextEntry = {
      if (pendingComplete != null) {
        val msg = pendingComplete
        pendingComplete = null
        someEntry(msg.date,msg)

      } else {
        val tp = dfr.next(recCache)
        val date = new DateTime(tp.getDate)
        val id = tp.getId.toString.intern  // we intern because there are likely a lot of points per track
        val cs = id // we could map this here

        val fpos = FlightPos(
          id,
          cs, // no CS
          LatLonPos.fromDegrees(tp.getLatitude, tp.getLongitude),
          Meters(tp.getAltitude),
          MetersPerSecond(tp.getSpeed),
          Degrees(tp.getHeading),
          date
        )

        if (tp.getCompleted) pendingComplete = FlightCompleted(id,cs,"?",date)

        someEntry(date, fpos)
      }
    }
  }

  override protected def instantiateReader (is: InputStream) = Some(new TPReader(is))
}
