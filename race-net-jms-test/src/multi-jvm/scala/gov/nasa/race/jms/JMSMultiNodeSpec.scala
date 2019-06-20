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

import akka.remote.testkit.MultiNodeConfig
import gov.nasa.race.core.RaceActorSystem
import gov.nasa.race.main.ConsoleMain
import gov.nasa.race.test.{RaceMultiNodeSpec, WrappedApp}
import gov.nasa.race.test.WrappedApp._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps


object JMSMultiNodeConfig extends MultiNodeConfig {
  // NOTE - the first node is not supposed to call system.terminate if it runs an actor system
  val jmsServerNode = role("jmsServer") // not a RaceActorsystem
  val raceNode = role("race")
  val jmsClientNode = role("jmsClient") // not a RaceactorSystem
}

import gov.nasa.race.jms.JMSMultiNodeConfig._

case object ServerUp
case object RaceUp
case object ClientUp
case object MessageReceived
case object ClientDone
case object RaceDone

/**
 * a multi-jvm integration test that performs JMS import and export.
 * This uses the RACE agnostic swimServer and swimClient, which are generic
 * JMS applications.
 */
class JMSMultiNodeSpec extends RaceMultiNodeSpec(JMSMultiNodeConfig) with AnyWordSpecLike {

  "A MultiNodeJMSSpec test" must {

    "wait for all nodes to start up" in {
      enterBarrier("startup")
    }

    "send messages from the JMS server to RACE, and send them from RACE to the JMS client" in {
      runOn(jmsServerNode) {
        val jmsServer = WrappedApp {
          JMSServer.main(Array[String]())
        }

        jmsServer whileExecuting {
          expectOutput(jmsServer, 10 seconds, "^enter command".r)
          sendMsg(raceNode, ServerUp)
          expectMsg(20 seconds, ClientUp)
          sendInput(jmsServer, "1") // send test message

          expectOutput(jmsServer, 10 seconds, "^enter command".r)
          expectMsg(20 seconds, RaceDone)
          sendInput(jmsServer, "9") // exit
          expectOutput(jmsServer, 10 seconds, "shutting down".r)

        } afterExecuting {
          enterBarrier("finished")

        } onFailure {
          sendInput(jmsServer, "9") // trying to shut it down gracefully
        } execute
      }

      runOn(raceNode) {
        val race = WrappedApp {
          RaceActorSystem.runEmbedded
          // ??? "--debug" causes ActiveMQ to not respond to wire format negotiation request from client ???
          val jmsConfigPath = baseResourceFile("jms.conf")
          ConsoleMain.main(Array("--info", jmsConfigPath.getAbsolutePath))
        }

        race beforeExecuting {
          expectMsg(10 seconds, ServerUp)

        } whileExecuting {
          expectOutput(race, 10 seconds, "jmsExporter is running".r)
          sendMsg(jmsClientNode, RaceUp)
          expectMsg(20 seconds, ClientDone)
          sendInput(race, "9") // exit
          expectOutput(race, 10 seconds, "terminated".r)

        } afterExecuting {
          sendMsg(jmsServerNode, RaceDone)
          enterBarrier("finished")

        } onFailure {
          sendInput(race, "9") // trying to shut it down gracefully
        } execute
      }

      runOn(jmsClientNode) {
        val jmsClient = WrappedApp {
          JMSClient.main(Array("--port", "61617"))
        }

        jmsClient beforeExecuting {
          expectMsg(20 seconds, RaceUp)

        } whileExecuting {
          expectOutput(jmsClient, 20 seconds, "jmsclient connected".r)
          sendMsg(jmsServerNode, ClientUp)
          expectOutput(jmsClient, 10 seconds, "got message: JMS TEST MESSAGE".r)
          sendInput(jmsClient, "9") // exit
          expectOutput(jmsClient, 10 seconds, "shutting down".r)

        } afterExecuting {
          sendMsg(raceNode, ClientDone)
          enterBarrier("finished")

        } onFailure {
          sendInput(jmsClient,"9") // trying to shut it down gracefully
        } execute
      }
    }

    // don't do this as a default action - only if each node finished properly
    //"terminate all nodes properly" in {
    //  enterBarrier("finished")
    //}
  }
}

class JMSMultiNodeSpecMultiJvmNode1 extends JMSMultiNodeSpec
class JMSMultiNodeSpecMultiJvmNode2 extends JMSMultiNodeSpec
class JMSMultiNodeSpecMultiJvmNode3 extends JMSMultiNodeSpec