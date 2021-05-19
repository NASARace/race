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

import gov.nasa.race.main.ConsoleMain
import gov.nasa.race.test.{JvmTestNode, NodeExecutor, RaceSpec, TestNode}
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

import java.io.File
import scala.concurrent.duration._
import scala.language.postfixOps

object KafkaServerNode extends JvmTestNode("kafka") {
  val kafkaDir = new File("tmp/kafka")

  def run(): Unit = {
    startAsDaemon {
      KafkaServer.main(Array("--topic", "kafkaTopic", "--clean"))
    }

    expectOutput("^enter command".r, 10.seconds)  // Kafka might take a moment to start up

    sendInput("1") // list topics
    expectOutput("kafkaTopic".r, 5.seconds)
    sendSignal("KafkaUp", RaceConsumerProducerNode)

    expectSignal("RaceDone", 20.seconds)
    sendInput("9") // terminate the server
  }

  override def beforeExecution(): Unit = {
    if (kafkaDir.isDirectory) {
      println(s"deleting existing $kafkaDir")
      FileUtils.deleteRecursively(kafkaDir)
    }
  }

  override def afterExecution(): Unit = {
    if (kafkaDir.isDirectory) {
      println(s"deleting $kafkaDir")
      FileUtils.deleteRecursively(kafkaDir)
    }
  }
}

object RaceConsumerProducerNode extends JvmTestNode("race") {
  def run(): Unit = {
    // KafkaProducer check the bootstrapServer when instantiated (i.e. during RaceInitialize) - if Kafka
    // isn't up and running at this point we get exceptions. In general, Kafka apparently assumes eternally
    // running servers - it does not deal well with concurrent start of server and clients
    expectSignal("KafkaUp", 10.seconds)

    startAsDaemon {
      ConsoleMain.main(Array("src/node-test/resources/kafka.conf"))
    }

    expectOutput("^enter command".r, 5.seconds)

    // BAD - if the KafkaConsumer is not configured with auto.offset.reset=latest we need to delay
    // in order to not loose the message we send
    //delay(1.second)

    sendInput("4") // send message
    expectOutput("enter channel".r)
    sendInput("|kafka-out")
    expectOutput("enter message content".r)
    sendInput("TEST")

    expectOutput("got message: TEST".r, 5.seconds)

    sendSignal("RaceDone", KafkaServerNode)

    sendInput("9") // terminate RACE
  }
}

/**
  * integration test for KafkaProducer/Consumer
  */
class KafkaMultiNodeSpec extends AnyFlatSpec with RaceSpec {

  "a KafkaProducer and KafkaConsumer actor" should "complete a roundtrip" in {
    NodeExecutor.execWithin( 30.seconds)(
      KafkaServerNode,
      RaceConsumerProducerNode
    )
  }
}


