/*
 * Copyright (c) 2024, United States Government, as represented by the
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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.core.RaceActor

/**
 * benchmarking actor for raw self message speed
 */
class RawMsgBenchActor (val config: Config) extends RaceActor {

  case class Cycle ( start_time: Long, round: Long )

  // more rounds -> per round goes down because of hotspot optimizer
  //val MAX_CYCLE = 1000000;
  val MAX_CYCLE = config.getLong("rounds")

  override def handleMessage: Receive = {
    case Cycle(start_time, round) =>
      if (round >= MAX_CYCLE) {
        val now = System.nanoTime()
        val dt = now - start_time
        println(s"$MAX_CYCLE messages sent in $dt ns -> ${dt/MAX_CYCLE} ns/msg")
        requestTermination

      } else {
        self ! Cycle( start_time, round + 1)
      }
  }

  override def onStartRaceActor (originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)) {
      self ! Cycle(System.nanoTime(), 0)
      true
    } else false
  }
}