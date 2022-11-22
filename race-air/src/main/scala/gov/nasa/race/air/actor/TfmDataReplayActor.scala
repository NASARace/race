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

package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.Replayer
import gov.nasa.race.air.TfmTracks
import gov.nasa.race.air.translator.TfmDataServiceParser
import gov.nasa.race.archive.{ArchiveReader, ConfiguredTAReader}


/**
  * a ReplayActor for TFM-DATA
  */
class TfmDataReplayActor (val config: Config) extends Replayer {
  type R = ArchiveReader

  class TfmDataServiceReader (conf: Config) extends ConfiguredTAReader(conf) {
    val parser = new TfmDataServiceParser

    override protected def parseEntryData(limit: Int): Any = {
      if (parser.checkIfTfmDataService(buf, 0, limit)) parser else None
    }
  }

  override def createReader = new TfmDataServiceReader(config)

  override def publishFiltered (msg: Any): Unit = {
    msg match {
      case parser: TfmDataServiceParser =>
        val tracks = parser.parseTfmDataServiceInitialized
        if (tracks.nonEmpty) super.publishFiltered(tracks)
      case tracks: TfmTracks => super.publishFiltered(tracks)
      case _ => // ignore
    }
  }
}
