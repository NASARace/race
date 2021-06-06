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

package gov.nasa.race.actor

import akka.event.Logging
import gov.nasa.race.core._
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.test.RaceActorSpec.Continue
import gov.nasa.race.uom.DateTime
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * unit test for ScheduledFileImportActor
  *
  * Note that our input file schedule.xml does contain path names of the messages to send, it has to be
  * updated accordingly if this test is moved
  */
class ScheduledFileImportActorSpec extends RaceActorSpec with AnyWordSpecLike {

  "a ScheduledFileImportActor" must {
    "publish all scheduled messages" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val testSchedule = baseResourceFile("schedule.xml")
        val conf = createTestConfig("schedule"->testSchedule.getPath, "write-to"->"testChannel")
        addTestActor[ScheduledFileImportActor]("scheduler", conf)

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        val t0 = System.currentTimeMillis()
        var n = 0
        expectBusMsg("testChannel", 15 seconds) {
          case BusEvent(_,msg:Any,_) =>
            n += 1
            val dt = (System.currentTimeMillis() - t0 + 500) / 1000 // do ad-hoc rounding to sec
            println(s"got scheduled message $n = '$msg' at +$dt sec")
            if (n == 1){
              msg should be("message 1")
              dt should be(2) // first message scheduled at +2 sec
              Continue
            } else {
              msg should be("message 2")
              dt should be(4) // second message scheduled at +4 sec
            }
        }

        terminateTestActors
      }
    }
  }
}
