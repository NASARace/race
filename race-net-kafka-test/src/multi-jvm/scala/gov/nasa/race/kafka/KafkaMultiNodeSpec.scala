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

// TODO - as of 11/19/2016 Kafka does not yet run under Scala 2.12, hence we have to disable the tests
//class KafkaMultiNodeSpecMultiJvmNode1 extends KafkaMultiNodeSpec
//class KafkaMultiNodeSpecMultiJvmNode2 extends KafkaMultiNodeSpec

