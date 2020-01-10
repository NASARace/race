/*
 * Copyright (c) 2019, United States Government, as represented by the
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
import gov.nasa.race.air.SfdpsTracks
import gov.nasa.race.air.translator.MessageCollectionParser
import gov.nasa.race.archive.{ArchiveReader, ConfiguredTAReader}
import gov.nasa.race.core.AccumulatingTopicIdProvider


/**
  * specialized Replayer for SFDPS tagged archives
  */
class SfdpsReplayActor(val config: Config) extends Replayer[ArchiveReader]
                                               with AccumulatingTopicIdProvider with ARTCCTopicIdMapper {
  class FilteringMessageCollectionParser extends MessageCollectionParser {
    override protected def filterSrc (artccId: String) = {
      !matchesAnyServedTopicId(artccId)
    }
  }

  class MessageCollectionReader(conf: Config) extends ConfiguredTAReader(conf) {
    val parser = new FilteringMessageCollectionParser

    override protected def parseEntryData(limit: Int): Any = {
      if (parser.checkIfMessageCollection(buf,0,limit)) parser else None
    }
  }

  override def createReader = new MessageCollectionReader(config)

  override def publishFiltered (msg: Any): Unit = {
    msg match {
      case parser: FilteringMessageCollectionParser =>
        val tracks = parser.parseMessageCollectionInitialized
        if (tracks.nonEmpty) super.publishFiltered(tracks)
      case tracks: SfdpsTracks => super.publishFiltered(tracks)
      case _ => // ignore
    }
  }
}
