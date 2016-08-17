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

package gov.nasa.race.actors.process

import java.io.File

import akka.event.Logging
import gov.nasa.race.core.RaceActorSpec
import org.joda.time.DateTime
import org.scalatest.WordSpecLike

/**
  * unit test for ProcessLauncher actor
  */
class ProcessLauncherSpec  extends RaceActorSpec with WordSpecLike {
  mkTestOutputDir

  "a ProcessLauncher" should {
    "create and terminate external processes" in {
      runRaceActorSystem(Logging.InfoLevel) {

        val logFile = testOutputFile("ExternalProc.log")
        logFile.delete // make sure a previous one is gone

        val launcher = addTestActor( classOf[ProcessLauncher], "launcher", createConfig(
          s"""process-name = "java"
            |process-args = ["-XshowSettings", "-classpath", "race-actors/target/scala-2.11/test-classes", "ExternalProc"]
            |logfile = ${logFile.getPath}
            |ensureKill = true
          """.stripMargin
        ))

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        Thread.sleep(1000) // give ExternalProc some time to run
        // check if log file got created
        assert(logFile.isFile && logFile.length > 0)

        terminateTestActors
      }
    }
  }
}
