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
package gov.nasa.race

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.main.ConsoleMain

/**
 * benchmarking actor for raw bus message speed
 */
class RawBusMsgBenchActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  case class Cycle ( start_time: Long, round: Long )

  val MAX_CYCLE = 2000000;

  override def handleMessage: Receive = {
    case BusEvent(_, Cycle(start_time, round), _) =>
      if (round >= MAX_CYCLE) {
        val now = System.nanoTime()
        val dt = now - start_time
        println(s"$MAX_CYCLE messages sent in $dt ns -> ${dt/MAX_CYCLE} ns/msg")
        requestTermination

      } else {
        publish( Cycle( start_time, round + 1))
      }
  }

  override def onStartRaceActor (originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)) {
      publish( Cycle(System.nanoTime(),0))
      true
    } else false
  }
}

object RawBusMsgBench {
  def main (args: Array[String]): Unit = {
    ConsoleMain.main(Array("src/resources/raw-bus-msg.conf"))
  }
}
