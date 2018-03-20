/*
 * Copyright (c) 2018, United States Government, as represented by the
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
import gov.nasa.race.config._
import org.joda.time.DateTime
import org.scalatest.WordSpecLike

object RetryRaceActorSpec {
  case class TestMessage (id: Int)
  case object Done

  var processingOrder: List[Int] = List.empty
}
import RetryRaceActorSpec._

class TestRetryActor1 (val config: Config) extends RetryRaceActor {

  var counter = 0

  override def handleMessage = handleRetryMessage orElse {
    case msg@TestMessage(id) =>
      counter += 1
      if (counter < 3) {
        println(s"retry $id")
        retry(msg)
      } else {
        println(s"process $id")
        processingOrder = id +: processingOrder
      }
  }
}

/**
  * regression test for RetryRaceActors
  */
class RetryRaceActorSpec extends RaceActorSpec with WordSpecLike {

  "a provider-subscriber chain" must {
    "start and stop publishing messages on demand" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val aRef = addTestActor(classOf[TestRetryActor1], "retrier", NoConfig)

        printTestActors
        initializeTestActors
        //printBusSubscriptions
        startTestActors(DateTime.now)

        aRef ! TestMessage(1)
        aRef ! TestMessage(2)
        aRef ! TestMessage(3)

        Thread.sleep(2000)

        terminateTestActors
      }
    }
  }

}
