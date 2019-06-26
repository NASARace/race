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
          expectOutput(server, 5 seconds, "^enter command".r)
          sendInput(server, "1")
          expectMsg(10 seconds, ClientDone)
          sendInput(server, "2")
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
          expectOutput(client, 10 seconds, "^client received message".r)
          sendMsg(serverNode, ClientDone)
        } execute
      }
    }
  }
}

class MultiNodeTestkitSpecMultiJvmNode1 extends MultiNodeTestkitSpec
class MultiNodeTestkitSpecMultiJvmNode2 extends MultiNodeTestkitSpec

//----------------------------------------------------------------------------- mockup apps

import java.net.{ServerSocket, Socket}
import java.io.{PrintWriter, InputStreamReader, BufferedReader}

object ServerApp {
  def main (args: Array[String]): Unit = {
    println("server running")

    val port: Int = 5432
    var listener: ServerSocket = null
    var sock: Socket = null
    var out: PrintWriter = null
    var in = new BufferedReader(new InputStreamReader(System.in))

    try {
      listener = new ServerSocket(port)
      println(s"server waiting for connection on port $port..")
      sock = listener.accept()
      println("server connected")
      out = new PrintWriter(sock.getOutputStream(),true)

      var cmd: String = ""
      do {
        println("enter command (1=send message, 2=terminate): ")
        cmd = in.readLine()
        if (cmd.equals("1")) {
          out.println("Hi there")
        }

      } while (!cmd.equals("2"))

    } finally {
      println("server terminating")
      if (out != null) out.close()
      if (sock != null) sock.close()
      if (listener != null) listener.close()
    }
  }
}

object ClientApp {
  def main (args: Array[String]): Unit = {
    println("client running")

    val host: String = "localhost"
    val port: Int = 5432
    var sock: Socket = null
    var in: BufferedReader = null

    try {
      sock = new Socket(host,port)
      println(s"client connected to $host:$port")
      in = new BufferedReader( new InputStreamReader(sock.getInputStream()))

      println("client waiting for message..")
      val msg = in.readLine()
      println(s"client received message '$msg'")

    } finally {
      println("client terminating")
      if (in != null) in.close()
      if (sock != null) sock.close()
    }
  }
}
