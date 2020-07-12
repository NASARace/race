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
import akka.pattern._
import com.typesafe.config.Config
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.config._
import gov.nasa.race.uom.DateTime
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.collection.immutable.List

object RetryRaceActorSpec {
  case class TestMessage (id: Int)
  case object Done

  val N = 5 // number of retry attempts
  val M = 3 // number of messages sent

  var processingOrder: List[Int] = List.empty
}
import RetryRaceActorSpec._

class TestRetryActor1 (val config: Config) extends RetryRaceActor {

  var counter = 0

  override def handleMessage = handleRetryMessage orElse {
    case msg@TestMessage(id) =>
      counter += 1
      if (counter < N) {
        println(s"retry $id")
        retry(msg)
      } else {
        println(s"process $id")
        processingOrder = id +: processingOrder
      }

    case Done =>
      if (processingOrder.size == M) sender() ! Done
  }
}

/**
  * regression test for RetryRaceActors
  */
class RetryRaceActorSpec extends RaceActorSpec with AnyWordSpecLike {

  "a RetryRaceActor" must {
    "process messages in the order in which they were received, regardless of retries" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val aRef = addTestActor[TestRetryActor1]("retryActor").self

        printTestActors
        initializeTestActors
        //printBusSubscriptions
        startTestActors(DateTime.now)

        for (i <- 1 to M) {
          aRef ! TestMessage(i)
          //Thread.sleep(100)
        }

        expectResponse(aRef ? Done, 1.second){
          case Done => println("done.")
          case other => fail(s"expected Done response, got $other")
        }

        terminateTestActors

        assert(processingOrder.reverse == List(1,2,3))
      }
    }
  }

}
