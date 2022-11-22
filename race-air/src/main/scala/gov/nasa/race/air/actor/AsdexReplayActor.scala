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
import gov.nasa.race.air.translator.{AsdexMsgParser, FullAsdexMsgParser}
import gov.nasa.race.air.AsdexTracks
import gov.nasa.race.archive.{ArchiveReader, ConfiguredTAReader}
import gov.nasa.race.core.AccumulatingTopicIdProvider

/**
  * a ReplayActor for ASDE-X
  */
class AsdexReplayActor(val config: Config) extends Replayer with AccumulatingTopicIdProvider with AirportTopicMapper {
  type R = ArchiveReader

  class FilteringAsdexMsgParser extends FullAsdexMsgParser {
    override protected def filterAirport (airportId: String) = {
      !matchesAnyServedTopicId(airportId)
    }
  }

  class AsdexMsgReader(conf: Config) extends ConfiguredTAReader(conf) {
    val parser = new FilteringAsdexMsgParser

    override protected def parseEntryData(limit: Int): Any = {
      if (parser.checkIfAsdexMsg(buf,0,limit)) return parser else None
    }
  }

  override def createReader = new AsdexMsgReader(config)

  override def publishFiltered (msg: Any): Unit = {
    msg match {
      case parser: AsdexMsgParser => // delayed parsing
        val tracks = parser.parseAsdexMsgInitialized
        if (tracks.nonEmpty) super.publishFiltered(tracks)
      case tracks: AsdexTracks => super.publishFiltered(tracks)
      case _ => // ignore
    }
  }
}
