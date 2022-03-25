/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveReader, ArchiveWriter}
import gov.nasa.race.common.ByteBufferReader
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createOutputStream}
import gov.nasa.race.track.{GgpArchiveReader, GgpArchiveWriter, GgpCsvParser}
import gov.nasa.race.{Failure, SuccessValue}

import java.nio.ByteBuffer

/**
  * actor that reads multicast (UDP) packets containing GpsGroundPos messages in CSV format and publishes them
  * as GpsGroundPos objects
  */
class GpsGroundPosImportActor (conf: Config) extends MulticastImportActor(conf) {

  class GpsGroundPosReader extends ByteBufferReader with GgpCsvParser {
    override val schema: String = "GpsGroundPosCsv"

    override def read(bb: ByteBuffer): Option[Any] = {
      if (initialize(bb)) {
        parseGpsGroundPos match {
          case SuccessValue(gps) => Some(gps)
          case Failure(msg) => warning(msg); None
        }
      } else None
    }
  }

  override protected def createReader: ByteBufferReader = new GpsGroundPosReader
}

/**
  * actor to archive GpsGroundPos messages
  *
  * note this should be done from within the importer if this is the only use, to avoid creating objects just
  * for the sake of archiving
  */
class GpsGroundPosArchiveActor (conf: Config) extends ArchiveActor(conf) {
  override protected def createWriter: ArchiveWriter = new GgpArchiveWriter(conf)
}

/**
  * actor to replay GpsGroundPos archives
  */
class GpsGroundPosReplayActor (conf: Config) extends ReplayActor(conf) {
  override def createReader: ArchiveReader = new GgpArchiveReader(conf)
}