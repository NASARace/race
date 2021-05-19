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

import akka.remote.testconductor.RoleName
import akka.remote.testkit._
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.FiniteDuration

abstract class RaceMultiNodeConfig extends MultiNodeConfig {
  // we use the otherwise frowned upon Java serialization for multi-node testing to minimize additional test dependencies
  commonConfig(ConfigFactory.parseString("""

    # multi-jvm testing uses remoting, which requires more configuration
    akka {
      actor {
        # we use otherwise frowned-upon Java serialization to avoid additional test dependencies (there are already enough)
        warn-about-java-serializer-usage = "off"
        allow-java-serialization = "on"

        # provider=remote is possible, but prefer cluster (according to doc 'remote' is low level)
        #provider = cluster
        provider = remote
      }
      remote {
        artery {
          transport = tcp # See Selecting a transport below
          canonical.hostname = "127.0.0.1"
          canonical.port = 0
        }
      }
      cluster {
        downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
      }
      akka.log-dead-letters-during-shutdown = "off"
    }
    """).withFallback(ConfigFactory.load()))
}

/*
    akka.remote.artery.canonical.hostname = "127.0.0.1"
    akka.remote.artery.canonical.port = 0
    akka.cluster.seed-nodes = [
      "akka://ClusterSystem@127.0.0.1:25251",
      "akka://ClusterSystem@127.0.0.1:25252"
    ]
    akka.cluster.downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
 */

abstract class RaceMultiNodeSpec (config: RaceMultiNodeConfig) extends MultiNodeSpec(config)
    with MultiNodeSpecCallbacks with Matchers with Suite with BeforeAndAfterAll
    with ImplicitSender with RaceSpec {

  val sysOut = System.out
  muteDeadLetters(classOf[Any])(system)

  override def beforeAll() = multiNodeSpecBeforeAll()
  override def afterAll() = multiNodeSpecAfterAll()

  def initialParticipants = roles.size

  def info (msg: String) = {
    sysOut.println(s"${scala.Console.WHITE}=== $msg${scala.Console.RESET}")
  }

  def sendMsg (role: RoleName, msg: Any) = {
    //info(s"${myself.name} sending ${msg} to ${role.name}")
    val remoteRef = system.actorSelection(node(role) / "system" / "testActor-1")
    remoteRef ! msg
  }

  override def enterBarrier (name: String*): Unit = {
    info(s"${myself.name} entering barrier ${name.mkString(",")}")
    super.enterBarrier(name:_*)
  }

  override def expectMsg[T](max: FiniteDuration, obj: T): T = {
    val ret = super.expectMsg(max,obj)
    info(s"${myself.name} received $obj")
    ret
  }
}
