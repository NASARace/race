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

package gov.nasa.race.actor

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.core.PublishingRaceActor
import gov.nasa.race.config.ConfigUtils._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


/**
  * a Publisher that is used for testing purposes
  */
class TestPublisher (val config: Config) extends PublishingRaceActor {
  case object PublishMessage

  val publishInterval = config.getFiniteDurationOrElse("interval", 5.seconds)
  val message = config.getStringOrElse("message", "test")
  var schedule: Option[Cancellable] = None

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    schedule = Some(scheduler.schedule(0.seconds, publishInterval, self, PublishMessage))
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    ifSome(schedule){ _.cancel }
  }

  override def handleMessage = {
    case PublishMessage => publish(message)
  }
}
