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

import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.uom.DateTime
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps

object BusSpec {
  // test messages
  case class PublishTest (msg: Any)
  case class TestMessage (payload: Int)

  class TestPublisher (val config: Config) extends PublishingRaceActor {
    override def handleMessage = {
      case PublishTest(msg) =>
        println(s"$name publishing $msg to channel [$writeToAsString]")
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
class BusSpec extends RaceActorSpec with AnyWordSpecLike {
  val testChannel = "testChannel"
  val logLevel = Logging.WarningLevel

  "a RaceActorSystem bus" must {
    "dispatch published messages to a RaceActorSpec probe" in {
      runRaceActorSystem(logLevel) {
        val publisher = addTestActor[TestPublisher]("publisher", "write-to"->testChannel).self

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
      runRaceActorSystem(logLevel) {
        val publisherRef = addTestActor[TestPublisher]("publisher", "write-to"->testChannel).self
        val subscriber = addTestActor[TestSubscriber]("subscriber", "read-from"->testChannel)

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        publisherRef ! PublishTest(TestMessage(42))

        sleep(1000)
        terminateTestActors

        assert(subscriber.gotIt)
      }
    }
  }
}
