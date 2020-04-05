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
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.uom.DateTime
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.Properties

/**
  * unit test for ProcessLauncher actor
  *
  * NOTE - this test needs to execute a Java process for which we have to set up the classpath explicitly,
  * so that it can find our ExternalProc.java test class
  * THIS MAKES THIS TEST BRITTLE WITH RESPECT TO MOVING ExternalProc OR UPDATING SCALA
  */
class ProcessLauncherSpec  extends RaceActorSpec with AnyWordSpecLike {
  mkTestOutputDir

  "a ProcessLauncher" should {
    "create and terminate external processes" in {
      runRaceActorSystem(Logging.InfoLevel) {

        val logFile = testOutputFile("ExternalProc.log")
        logFile.delete // make sure a previous one is gone

        val scalaVer = Properties.versionNumberString.substring(0,4)

        val conf = createConfig(
          s"""process-name = "java"
              process-args = ["-XshowSettings", "-classpath", "race-core-test/target/scala-${scalaVer}/test-classes", "ExternalProc"]
              logfile = ${logFile.getPath}
              ensureKill = true
          """)
        addTestActor[ProcessLauncher]("launcher",conf)

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        Thread.sleep(1000) // give ExternalProc some time to run

        println(s"checking non-empty log file: $logFile")
        assert(logFile.isFile && logFile.length > 0)
        println("Ok.")

        terminateTestActors
      }
    }
  }
}
