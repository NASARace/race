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
package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, SubscribingRaceActor}
import gov.nasa.race.util.ArrayUtils

/**
  * an actor that simply republishes its input on different channels.
  * Can be used to disambiguate message processing based on input channels (not message content), but
  * be aware this incurs runtime costs
  *
  * NOTE - readFrom and writeTo have to be non-overlapping to avoid endless loops. This will be checked on
  * initialization
  */
class RePublishActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    if (super.onInitializeRaceActor(raceContext, actorConf)){
      if (ArrayUtils.intersect(readFrom,writeTo)){
        error("read-from and write-to channel sets not disjunct")
        false
      } else true
    } else false
  }

  override def handleMessage = {
    case BusEvent(_,msg: Any,_) => publish(msg)
  }
}
