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

package gov.nasa.race.common

import akka.actor.ActorSelection
import org.scalatest.{Suite, BeforeAndAfterAll, WordSpecLike, Matchers}
import akka.testkit.ImplicitSender
import akka.remote.testkit._
import akka.remote.testconductor.RoleName

import scala.concurrent.duration.FiniteDuration

abstract class RaceMultiNodeSpec (config: MultiNodeConfig) extends MultiNodeSpec(config)
    with MultiNodeSpecCallbacks with Matchers with Suite with BeforeAndAfterAll
    with ImplicitSender {

  val sysOut = System.out
  muteDeadLetters(classOf[Any])(system)

  override def beforeAll() = multiNodeSpecBeforeAll()
  override def afterAll() = multiNodeSpecAfterAll()

  def initialParticipants = roles.size

  def info (msg: String) = {
    sysOut.println(s"${scala.Console.WHITE}=== $msg${scala.Console.RESET}")
  }

  def sendMsg (role: RoleName, msg: Any) = {
    info(s"${myself.name} sending ${msg} to ${role.name}")
    system.actorSelection(node(role) / "system" / "testActor-1") ! msg
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
