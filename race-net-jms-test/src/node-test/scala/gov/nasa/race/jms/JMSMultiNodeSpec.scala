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

package gov.nasa.race.jms

import gov.nasa.race.main.ConsoleMain
import gov.nasa.race.test.{JvmTestNode, NodeExecutor, RaceSpec}
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration._
import scala.language.postfixOps

object JmsServerNode extends JvmTestNode("server") {
  def run(): Unit = {
    startAsDaemon {
      JMSServer.main(Array.empty[String])
    }

    expectOutput("^enter command".r, 5.seconds)
    sendSignal("ServerUp", RaceNode)
    expectSignal( "ClientUp", 10.seconds)
    sendInput("1") // send test message

    expectOutput("^enter command".r, 5.seconds)
    expectSignal("RaceDone", 10.seconds)

    sendInput("9") // exit
  }
}

object RaceNode extends JvmTestNode("race") {
  def run(): Unit = {
    expectSignal("ServerUp", 10.seconds)

    startAsDaemon {
      ConsoleMain.main(Array(baseResourceFile("jms.conf").getCanonicalPath))
    }

    expectOutput("^enter command".r, 5.seconds)
    sendSignal("RaceUp", RaceClientNode)

    expectSignal("ClientDone", 10.seconds)

    sendSignal("RaceDone", JmsServerNode)
    sendInput("9") // exit
  }
}

object RaceClientNode extends JvmTestNode("client") {
  def run(): Unit = {
    expectSignal("RaceUp", 10.seconds)

    startAsDaemon {
      JMSClient.main(Array("--port", "61617")) // this connects to the embedded RACE ActiveMQ broker
    }

    expectOutput("jmsclient connected".r, 10.seconds)
    sendSignal("ClientUp", JmsServerNode)
    expectOutput("got message: JMS TEST MESSAGE".r, 10.seconds)  // this is the message sent by the JmsServer and relayed by RACE

    sendSignal("ClientDone", RaceNode)
    sendInput("9") // exit
  }
}

class JmsMultiNodeSpec extends AnyFlatSpec with RaceSpec {
  "JmsImport- and ExportActors" should "replay a test message from a JMS server to a RACE JMS client" in {
    NodeExecutor.execWithin( 30.seconds)(
      JmsServerNode,
      RaceNode,
      RaceClientNode
    )
  }
}
