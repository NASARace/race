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
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import gov.nasa.race.core.{RaceActor, RaceContext, askForResult}
import gov.nasa.race.main.ConsoleMain

import scala.concurrent.duration.DurationInt
import scala.language.{implicitConversions, postfixOps}


case object StartAsking
case class Question()
case class Answer ( whatIsIt: Int )

/**
 * benchmarking actor for raw self message speed
 */
class AskBenchResponder (val config: Config) extends RaceActor {
  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    RawAskBench.responderRef = self
    super.onInitializeRaceActor(rc, actorConf)
  }

  override def handleMessage: Receive = {
    case Question() => sender() ! Answer(42)
  }
}

class AskBenchQuestioner (val config: Config) extends RaceActor {
  implicit val timeout: Timeout = Timeout(System.getProperty("race.timeout", "15").toInt seconds)
  val MAX_ROUND = config.getLong("rounds")

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    self ! StartAsking
    super.onStartRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case StartAsking =>
      val responderRef = RawAskBench.responderRef
      println(s"start asking $responderRef ..")
      var round = 0
      val startTime = System.nanoTime()

      while (round < MAX_ROUND) {
        askForResult( responderRef ? Question()) {
          case Answer(n) =>
            if (n != 42) throw new RuntimeException(s"wrong answer on round $round: $n")
          case other => throw new RuntimeException(s"wrong result: $other")
        }
        round += 1
      }
      val now = System.nanoTime()
      val elapsed = now - startTime
      println(s"$round rounds in ${elapsed/1000} μs -> ${elapsed / round} ns per ask cycle")
      requestTermination
  }
}

object RawAskBench {
  var responderRef: ActorRef = null

  def main (args: Array[String]): Unit = {
    ConsoleMain.main(Array("config/local/raw-ask.conf"))
  }
}