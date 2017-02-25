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

package gov.nasa.race.core

import Messages._
import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.util.ThreadUtils._
import org.joda.time.DateTime
import org.scalatest.WordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps

object BusSpec {
  // test messages
  case class PublishTest (msg: Any)
  case class TestMessage (payload: Int)

  class TestPublisher (val config: Config) extends PublishingRaceActor {
    override def handleMessage = {
      case PublishTest(msg) =>
        println(s"$name publishing $msg to channel $writeTo")
        publish(msg)
    }
  }

  class TestSubscriber (val config: Config) extends SubscribingRaceActor {
    var gotIt = false

    override def handleMessage = {
      case BusEvent(channel,TestMessage(payload),_) =>
        println(s"$name received TestMessage($payload)")
        gotIt = (payload == 42)
    }
  }
}
import gov.nasa.race.core.BusSpec._

/**
  * tests for RaceActor pub/sub communication through a Race Bus
  */
class BusSpec extends RaceActorSpec with WordSpecLike {
  val testChannel = "testChannel"

  "a RaceActorSystem bus" must {
    "dispatch published messages to a RaceActorSpec probe" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val publisher = addTestActor(classOf[TestPublisher], "publisher", createConfig(s"write-to = $testChannel"))

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        expectBusMsg(testChannel, 5 seconds, publisher ! PublishTest(TestMessage(42))) {
          case e @ BusEvent(channel,TestMessage(payload),originator) =>
            println(s"probe got message $e")
            assert(channel == testChannel)
            assert(payload == 42)
            assert(originator == publisher)
            println("all assertions passed")
        }

        terminateTestActors
      }
    }
  }

  "a RaceActorSystem bus" must {
    "dispatch published messages to subscribed RaceActors" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val publisher = addTestActor(classOf[TestPublisher], "publisher", createConfig(s"write-to = $testChannel"))
        val subscriber = addTestActor(classOf[TestSubscriber], "subscriber", createConfig(s"read-from = $testChannel"))

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        publisher ! PublishTest(TestMessage(42))

        sleep(1 second)
        terminateTestActors

        assert(actor(subscriber).gotIt)
      }
    }
  }
}
