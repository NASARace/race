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

import com.typesafe.config.Config
import gov.nasa.race.actor.Replayer
import gov.nasa.race.air.TaisTracks
import gov.nasa.race.air.translator.TATrackAndFlightPlanParser
import gov.nasa.race.archive.{ArchiveReader, ConfiguredTAReader}
import gov.nasa.race.core.AccumulatingTopicIdProvider


/**
  * specialized Replayer for TAIS tagged archives
  */
class TaisReplayActor(val config: Config) extends Replayer with AccumulatingTopicIdProvider with TRACONTopicIdMapper {
  type R = ArchiveReader

  class FilteringTATrackAndFlightPlanParser extends TATrackAndFlightPlanParser {
    override protected def filterSrc (traconId: String) = {
      !matchesAnyServedTopicId(traconId)
    }
  }

  class TATrackAndFlightPlanReader(conf: Config) extends ConfiguredTAReader(conf) {
    val parser = new FilteringTATrackAndFlightPlanParser

    override protected def parseEntryData(limit: Int): Any = {
      if (parser.checkIfTATrackAndFlightPlan(buf,0,limit)) parser else None
    }
  }

  override def createReader = new TATrackAndFlightPlanReader(config)

  override def publishFiltered (msg: Any): Unit = {
    msg match {
      case parser: TATrackAndFlightPlanParser =>
        val tracks = parser.parseTATrackAndFlightPlanInitialized
        if (tracks.nonEmpty) super.publishFiltered(tracks)
      case tracks: TaisTracks => super.publishFiltered(tracks)
      case _ => // ignore
    }
  }
}
