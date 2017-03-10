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

package gov.nasa.race.kafka

import java.io.File

import akka.remote.testkit.MultiNodeConfig
import gov.nasa.race.core.RaceActorSystem
import gov.nasa.race.main.ConsoleMain
import gov.nasa.race.test.WrappedApp._
import gov.nasa.race.test.{RaceMultiNodeSpec, WrappedApp}
import org.scalatest.WordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps


object KafkaMultiNodeConfig extends MultiNodeConfig {
  // NOTE - the first node is not supposed to call system.terminate if it runs an actor system
  val kafkaServerNode = role("kafkaServer") // not a RaceActorsystem
  val raceNode = role("race")
}

import KafkaMultiNodeConfig._

case object KafkaUp
case object RaceUp
case object MessageReceived
case object RaceDone


/**
  * integration test for KafkaProducer/Consumer
  */
class KafkaMultiNodeSpec extends RaceMultiNodeSpec(KafkaMultiNodeConfig) with WordSpecLike {

  "A KafkaMultiNodeSpec test" must {

    "wait for all nodes to start up" in {
      enterBarrier("startup")
    }

    runOn(kafkaServerNode) {
      val kafkaServer = WrappedApp(KafkaServer.main(Array("--topic", "test", "--clean")))

      kafkaServer beforeExecuting {

      } whileExecuting {
        expectOutput(kafkaServer, 10 seconds, "^enter command".r)

        sendMsg(raceNode, KafkaUp)

        expectMsg(40 seconds, RaceDone)
        sendInput(kafkaServer, "9") // exit
        //expectOutput(kafkaServer, 10 seconds, "shutting down".r)

      } afterExecuting {
        enterBarrier("finished")

      } onFailure {
        //sendInput(kafkaServer, "9") // trying to shut it down gracefully
      } execute
    }

    runOn(raceNode) {
      val race = WrappedApp {
        RaceActorSystem.runEmbedded
        val jmsConfigPath = baseResourceFile("kafka.conf")
        ConsoleMain.main(Array(jmsConfigPath.getAbsolutePath))
      }

      race beforeExecuting {
        expectMsg(10 seconds, KafkaUp)

      } whileExecuting {
        expectOutput(race, 10 seconds, "^enter command".r)
        sendInput(race, "4") // send test message
        expectOutput(race, 15 seconds, "enter channel".r)
        sendInput(race, "|kafka-out") // send message to channel
        expectOutput(race, 15 seconds, "enter message content".r)
        sendInput(race, "TEST")  // message text

        expectOutput(race, 30 seconds, "got on channel: 'kafka-in' message: 'TEST'".r)
        sendInput(race, "9")
        //expectOutput(race, 10 seconds, "shutting down".r)

      } afterExecuting {
        sendMsg(kafkaServerNode, RaceDone)
        enterBarrier("finished")

      } onFailure {
        //sendInput(race, "9") // trying to shut it down gracefully
      } execute
    }
  }
}

class KafkaMultiNodeSpecMultiJvmNode1 extends KafkaMultiNodeSpec
class KafkaMultiNodeSpecMultiJvmNode2 extends KafkaMultiNodeSpec

