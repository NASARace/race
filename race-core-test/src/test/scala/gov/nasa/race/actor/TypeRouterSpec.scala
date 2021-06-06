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
import gov.nasa.race.core.BusEvent
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpecLike
import scala.concurrent.duration._

object TypeRouterSpec {
  case class Foo (val data: Int)
}

/**
  * regression test for TypeRouter actor
  */
class fTypeRouterSpec extends RaceActorSpec with AnyFlatSpecLike {
  import TypeRouterSpec._

  "a TypeRouter actor" should "publish incoming messages to the right channels" in {
    runRaceActorSystem(Logging.WarningLevel) {
      val conf = createConfig(  // name and class are automatically added
        s"""
           | read-from = "/input"
           | routes = [
           |   { type = "java.lang.String", write-to = ["/strings"] },
           |   { type = "${classOf[Foo].getName}", write-to = ["/foos"] }
           | ]
         """.stripMargin)

      addTestActor[TypeRouter]("router",conf)
      initializeTestActors

      printTestActors
      printBusSubscriptions

      sleep(1000)
      startTestActors(DateTime.now)

      var stringIn = "I am a String"
      var stringOut: String = null
      var fooIn = Foo(42)
      var fooOut: Foo = null

      expectBusMsg("/strings", 2.seconds, publish("/input", stringIn)) {
        case BusEvent(_, msg: String, _) =>
          println(s"got a String on /strings : $msg")
          stringOut = msg
        case BusEvent(_,msg,_) => fail(s"unexpected msg on /strings: $msg")
      }

      expectBusMsg("/foos", 2.seconds, publish("/input", fooIn)) {
        case BusEvent(_, msg: Foo, _) =>
          println(s"got a Foo on /foos : $msg")
          fooOut = msg
        case BusEvent(_,msg,_) => fail(s"unexpected msg on /foos: $msg")
      }

      stringIn.shouldEqual(stringOut)
      fooIn.shouldEqual(fooOut)

      terminateTestActors
    }
  }
}
