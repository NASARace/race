/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.core

import com.typesafe.config.Config
import gov.nasa.race.common.LongStats
import gov.nasa.race.core.Messages.PingRaceActorResponse

/**
  * housekeeping meta-data for top level RaceActors
  *
  * NOTE - in a live RAS this is only to be used by Master for now,
  * otherwise we would have to synchronize
  */
class ActorMetaData(val actorConfig: Config) {

  var lastPingTime: Long = 0
  var lastPingResponse: PingRaceActorResponse = null
  var isUnresponsive: Boolean = false // this needs to be set explicitly to avoid blocking a pre-start termination

  val latencyStats = new LongStats // msg.tReceiveNanos - msg.tSendNanos
}
