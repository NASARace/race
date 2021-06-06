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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, SubscribingRaceActor}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


case object TestMessage

/**
  * a RaceActor for testing purposes that can be configured to fail in various ways.
  *
  * The default behavior is to print its phases/actions sync on the console, and to pass
  * through received messages. This can be used to create chains of RaceActors that fail
  * somewhere downstream
  *
  * Note - this is mostly for testing RACE infrastructure itself, but also for testing
  * the behavior of RaceActor implementations with respect to system failures, hence we
  * add it to our standard actor portfolio instead of keeping it inside of race-core-test
  *
  * TODO - add more RA protocol violations
  */
class TestActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  println(s"## $name entered contructor")
  val handleAll = config.getBooleanOrElse("handle-all", false)

  checkCrash("crash-ctor")
  checkTimeout("timeout-ctor")

  override def onInitializeRaceActor (raceContext: RaceContext, actorConf: Config): Boolean = {
    println(s"## $name entered onInitializeRaceActor")

    checkCrash("crash-init")
    checkTimeout("timeout-init")
    checkFail("fail-init"){
      ifSome(config.getOptionalString("send-on-init")) { publish }
      super.onInitializeRaceActor(raceContext, actorConf)
    }
  }

  override def onStartRaceActor (originator: ActorRef): Boolean = {
    println(s"## $name entered onStartRaceActor")

    checkCrash("crash-start")
    checkTimeout("timeout-start")
    checkFail("fail-start"){
      ifSome(config.getOptionalString("send-on-start")) { publish }
      ifSome(config.getOptionalString("schedule-on-start")) { msg =>
        val delay = config.getFiniteDurationOrElse("schedule-delay", 1.second)
        scheduler.scheduleOnce(delay){ publish(msg) }
      }
      super.onStartRaceActor(originator)
    }
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    println(s"## $name entered onTerminateRaceActor")

    checkCrash("crash-terminate")
    checkTimeout("timeout-terminate")
    checkFail("fail-terminate"){ super.onTerminateRaceActor(originator) }
  }

  override def handleMessage: Receive = {
    case BusEvent(chan,msg,sender) =>
      println(s"## $name received $msg on $chan sent by ${sender.path.name}")

      checkCrash("crash-message")
      checkTimeout("timeout-message")

      println(s"## $name publishing $msg")
      publish(msg)

    case other if handleAll =>
      println(s"## $name erroneously handling $other")
  }

  override def handlePingRaceActor (heartBeat: Long, tPing: Long): Unit = {
    if (!config.getBooleanOrElse("ignore-heartbeat", false)) {
      super.handlePingRaceActor(heartBeat, tPing)
    }
  }

  //--- the configurable fail actions

  protected def checkFail (key: String)(succeedAction: =>Boolean): Boolean = {
    if (config.getBooleanOrElse(key, false)) {
      val msg = s"$name failing on $key"
      println(s"## $name now failing on $key")
      error(msg)
      false
    } else {
      succeedAction
    }
  }

  protected def checkCrash (key: String) = {
    if (config.getBooleanOrElse(key, false)) {
      val msg = s"$name throwing $key"
      println(s"## $name now throwing exception on $key")
      throw new RuntimeException(msg)
    }
  }

  protected def checkTimeout (key: String) = {
    if (config.getBooleanOrElse(key, false)) {
      val msg = s"$name timing out on $key"
      println(s"## $name now timing out on $key")
      Thread.sleep(Long.MaxValue)
    }
  }
}
