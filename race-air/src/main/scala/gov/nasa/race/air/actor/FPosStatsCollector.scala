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
import gov.nasa.race.actor.TSStatsCollectorActor
import gov.nasa.race.air.{FlightCompleted, FlightPos}
import gov.nasa.race.common.{TSEntryData, TSStatsData}
import gov.nasa.race.core.ClockAdjuster
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}

/**
  * actor that collects update statistics for FlightPos objects
  */
class FPosStatsCollector (val config: Config)
   extends TSStatsCollectorActor[String,FlightPos,TSEntryData[FlightPos],FlightPosStatsData] with ClockAdjuster {

  override def createTSStatsData = {
    new FlightPosStatsData {
      buckets = createBuckets
    }
  }
  override def createTSEntryData (t: Long, fpos: FlightPos) = new TSEntryData(t,fpos)

  override def handleMessage = {
    case BusEvent(_, fpos: FlightPos, _) =>
      checkClockReset(fpos.date)
      updateActive(fpos.cs,fpos)

    case BusEvent(_, fcomplete: FlightCompleted, _) =>
      checkClockReset(fcomplete.date)
      removeActive(fcomplete.cs)

    // we do our own flight dropped handling since we also check upon first report

    case RaceTick =>
      checkDropped
      publish(statsSnapshot)
  }
}

class FlightPosStatsData extends TSStatsData[FlightPos,TSEntryData[FlightPos]] {
  override def isDuplicate(fpos: FlightPos, last: FlightPos) = {
    fpos.position =:= last.position && fpos.altitude =:= last.altitude
  }
}
