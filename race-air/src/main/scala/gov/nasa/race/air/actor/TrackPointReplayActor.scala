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

import java.io.{File, InputStream}
import com.typesafe.config.Config
import gov.nasa.race.actor.ReplayActor
import gov.nasa.race.air.FlightPos
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackCompleted
import gov.nasa.race.track.avro.{TrackIdRecord, TrackPoint}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed
import gov.nasa.race.uom.Speed._
import org.apache.avro.file.{DataFileReader, DataFileStream}
import org.apache.avro.specific.SpecificDatumReader
import gov.nasa.race.uom.DateTime

/**
  * a ReplayActor that reads Avro TrackPoint archives and emits them as FlightMessage objects
  */
class TrackPointReplayActor (config: Config) extends ReplayActor(config) {

  override def createReader = new TrackPointReader(config.getConfig("reader")) // hardwired reader, we ignore class
}

/**
  * a ConfigurableArchiveReader for TrackPoint records from Avro archives
  *
  * we use a stream based reader because that way we also support compressed
  * Avro archives (up to 30% compression for TrackPoint archives)
  *
  * TODO - we might extend FlightPos with a 'completed' state, in which case the 'completed' cache can be dropped
  */
class TrackPointReader (val iStream: InputStream, val pathName: String="<unknown>", val idMapFile: Option[File] = None)
                                                                                                extends ArchiveReader {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf), conf.getOptionalFile("id-map"))

  val dfr = new DataFileStream(iStream, new SpecificDatumReader[TrackPoint])
  val recCache = new TrackPoint
  var pendingComplete: TrackCompleted = null // to be set if we encounter a completed TrackPoint

  val idMap: Map[String,String] = initIdMap

  def initIdMap: Map[String,String] = {
    idMapFile match {
      case Some(file) =>
        val dfr = new DataFileReader[TrackIdRecord](file,new SpecificDatumReader[TrackIdRecord])
        var rec = new TrackIdRecord
        var map = Map.empty[String,String]
        while (dfr.hasNext){
          val r = dfr.next(rec)
          map = map + (r.getId.toString -> r.getCs.toString)
        }
        map
      case None => Map.empty[String,String]
    }
  }

  override def hasMoreArchivedData = (pendingComplete != null) || dfr.hasNext
  override def close(): Unit = dfr.close

  override def readNextEntry(): Option[ArchiveEntry] = {
    if (pendingComplete != null) {
      val msg = pendingComplete
      pendingComplete = null
      archiveEntry(msg.date,msg)

    } else {
      val tp = dfr.next(recCache)
      val date = DateTime.ofEpochMillis(tp.getDate)
      val id = tp.getId.toString.intern  // we intern because there are likely a lot of points per track
      val cs = idMap.getOrElse(id,id) // we could map this here

      val fpos = new FlightPos(
        id,
        cs, // no CS
        GeoPosition.fromDegreesAndMeters(tp.getLatitude, tp.getLongitude, tp.getAltitude),
        MetersPerSecond(tp.getSpeed),
        Degrees(tp.getHeading),
        Speed.UndefinedSpeed,       // TODO - we only store this in FullTrackPoints
        date
      )

      if (tp.getCompleted) pendingComplete = TrackCompleted(id,cs,"?",date)

      archiveEntry(date, fpos)
    }
  }
}