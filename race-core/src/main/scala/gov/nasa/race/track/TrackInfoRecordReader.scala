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

import java.io.File

import com.typesafe.config.Config
import gov.nasa.race.track.avro.TrackInfoRecord
import gov.nasa.race.util.FileUtils
import org.apache.avro.file.DataFileReader
import org.apache.avro.specific.SpecificDatumReader
import gov.nasa.race.uom.DateTime
import scala.collection.mutable.{Map=>MMap}

/**
  * a TrackInfoReader that initializes from a Avro TrackInfoRecord archive
  */
class TrackInfoRecordReader  (val config: Config) extends TrackInfoReader {

  val pathName = config.getString("pathname")

  override def initialize (filterMap: Option[MMap[String,Tracked3dObject]]): Seq[TrackInfo] = {
    FileUtils.existingNonEmptyFile(pathName) match {
      case Some(file) => read(file)
      case None => Seq.empty
    }
  }

  def read (file: File): Seq[TrackInfo] = {
    val dfr = new DataFileReader[TrackInfoRecord](file,new SpecificDatumReader[TrackInfoRecord])
    var rec = new TrackInfoRecord
    var list = Seq.empty[TrackInfo]
    while (dfr.hasNext) {
      rec = dfr.next(rec)
      val ti = new TrackInfo(
        rec.getId.toString,
        rec.getCs.toString,
        Some(rec.getCategory.toString),
        Some(rec.getVehicleType.toString),
        Some(rec.getDeparturePoint.toString),
        Some(rec.getArrivalPoint.toString),
        DateTime.ofEpochMillis(rec.getEtd),
        DateTime.UndefinedDateTime, // no atd
        DateTime.ofEpochMillis(rec.getEta),
        DateTime.UndefinedDateTime, // no ata
        None // no planned route
      )
      list = ti +: list
    }
    list
  }
}
