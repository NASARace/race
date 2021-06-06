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

import akka.event.Logging
import gov.nasa.race.core._
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.util.FileUtils
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpecLike
import scala.concurrent.duration._

/**
  * regression test for XmlValidator actor
  */
class XmlValidatorSpec extends RaceActorSpec with AnyFlatSpecLike {

  "a XmlValidatorActor" should "route valid and invalid messages to different channels" in {
    runRaceActorSystem(Logging.WarningLevel) {

      val schemaFile = baseResourceFile("schema.xsd")
      val invalidMessage = FileUtils.fileContentsAsUTF8String(baseResourceFile("invalid.xml"))
      val validMessage = FileUtils.fileContentsAsUTF8String(baseResourceFile("valid.xml"))

      val conf = createConfig(s"""
              schemas = [ "${schemaFile.getPath}" ]
              read-from = "/input"
              write-to-pass = "/accepted"
              write-to-fail = "/rejected"
          """)
      addTestActor[XmlValidator]("validator", conf)

      printTestActors
      initializeTestActors
      startTestActors(DateTime.now)

      expectBusMsg("/accepted", 2.seconds, publish("/input", validMessage)) {
        case BusEvent(_, msg: Any, _) => println(f"valid message did pass $msg%30.30s..")
      }

      expectBusMsg("/rejected", 2.seconds, publish("/input", invalidMessage)) {
        case BusEvent(_, msg: Any, _) => println(f"invalid message did not pass $msg%30.30s..")
      }

      terminateTestActors
    }
  }
}
