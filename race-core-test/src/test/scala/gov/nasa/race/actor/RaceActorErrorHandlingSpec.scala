/*
 * Copyright (c) 2017, United States Government, as represented by the
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
import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.core.RaceActor
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.uom.DateTime
import gov.nasa.race.config.NoConfig
import org.scalatest.flatspec.AnyFlatSpecLike

object RaceActorErrorHandlingSpec {
  object CrashNow
  object ProcessThis

  var thisProcessed = false

  class MessageCrasher(val config: Config) extends RaceActor {
    override def handleMessage = {
      case CrashNow => throw new RuntimeException("I'm crashing in handleMessage")
      case ProcessThis => thisProcessed = true
    }
  }

  class StartCrasher (val config: Config) extends RaceActor {
    override def onStartRaceActor(originator: ActorRef): Boolean = {
      throw new RuntimeException("I'm crashing in onStartRaceActor")
    }
  }
}

/**
  * regression tests for RaceActor error handling
  */
class RaceActorErrorHandlingSpec extends RaceActorSpec with AnyFlatSpecLike {
  import RaceActorErrorHandlingSpec._

  "master actor" should "detect and report exceptions during RaceActor handleMessage execution" in {
    runRaceActorSystem(Logging.InfoLevel) {

      val actorRef = addTestActor[MessageCrasher]("crasher").self

      printTestActors
      initializeTestActors
      startTestActors(DateTime.now)

      actorRef ! CrashNow

      // actor should still be running since we have a RESUME supervisor strategy
      actorRef ! ProcessThis
      sleep(100)
      assert(thisProcessed)

      terminateTestActors
    }
  }

  "master actor" should "detect and report exceptions during (sync) onStartRaceActor execution" in {
    runRaceActorSystem(Logging.InfoLevel) {
      expectToFail {
        addTestActor[StartCrasher]("crasher")

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)
        terminateTestActors
      }
    }
  }
}
