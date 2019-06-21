/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import gov.nasa.race.test.WrappedApp._
import akka.remote.testkit.MultiNodeConfig
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps

object MultiNodeTestkitSpecConfig extends MultiNodeConfig {
  val serverNode = role("server")
  val clientNode = role("client")
}
import MultiNodeTestkitSpecConfig._

case object ServerUp
case object ClientUp
case object ClientDone


class MultiNodeTestkitSpec extends RaceMultiNodeSpec(MultiNodeTestkitSpecConfig) with AnyWordSpecLike {
  "A MultiNodeTestkitSpec test" must {

    "wait for all nodes to start up" in {
      enterBarrier("startup")
    }

    "send message to client and wait for response" in {

      //---------------------------------------------------------------------- server
      runOn(serverNode){
        val server = WrappedApp {
          ServerApp.main(Array[String]())
        }

        server whileExecuting {
          expectOutput(server, 10 seconds, "^server running".r)
          sendMsg(clientNode, ServerUp)
          expectMsg(15 seconds, ClientUp)
          // send message to 
        } execute
      }

      //---------------------------------------------------------------------- client
      runOn(clientNode) {
        val client = WrappedApp {
          ClientApp.main(Array[String]())
        }

        client beforeExecuting {
          expectMsg(15 seconds, ServerUp)

        } whileExecuting {
          expectOutput(client, 10 seconds, "^client running".r)
          sendMsg(serverNode, ClientUp)
        } execute
      }
    }
  }
}

class MultiNodeTestkitSpecMultiJvmNode1 extends MultiNodeTestkitSpec
class MultiNodeTestkitSpecMultiJvmNode2 extends MultiNodeTestkitSpec

//--- mock apps

object ServerApp {
  def main (args: Array[String]): Unit = {
    println("server running")
  }
}

object ClientApp {
  def main (args: Array[String]): Unit = {
    println("client running")
  }
}
