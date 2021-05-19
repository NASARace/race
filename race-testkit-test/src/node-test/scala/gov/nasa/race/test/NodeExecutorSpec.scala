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
package gov.nasa.race.test

import gov.nasa.race.main.ConsoleMain
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration._

object RaceNode extends JvmTestNode("race") {
  def run(): Unit = {
    //--- start the SUT
    startAsDaemon {
      ConsoleMain.main(Array("src/test/resources/gov/nasa/race/test/probe.conf"))
    }

    //--- the automation sequence

    expectOutput("^enter command".r)  // wait until RACE is up and running

    matchIOSequence(
      "4" -> ("enter channel".r, 1.second),
      "|input" -> ("enter message content".r, 1.second),
      "TEST" -> ("got message: TEST".r, 2.seconds)
    )

    sendInput("9") // exit menu item
  }
}

class NodeExecutorSpec extends AnyFlatSpec with RaceSpec {

  "a NodeExecutor" should "execute an automated RACE instance" in {
    NodeExecutor.execWithin(20.seconds)(
      RaceNode
    )
  }
}
