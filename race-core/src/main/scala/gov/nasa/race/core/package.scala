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

package gov.nasa.race

import java.util.concurrent.TimeoutException

import akka.actor.{ActorIdentity, ActorRef, Identify}
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.{implicitConversions, postfixOps}

/**
  * package gov.nasa.race.core is the location for all system types that implement RaceActorSystems and RaceActors,
  * including their initialization. This is the essence of RACE, that most notably includes
  *
  *  - RaceActorSystem itself
  *  - the MasterActor
  *  - the RaceActor base types (RaceActor, PublishingRaceActor, SubscribingRaceActor etc.)
  */
package object core {

  // standard channels
  final val PROVIDER_CHANNEL = "/race/provider"
  final val LOCAL_CHANNEL = "/local/"

  // common type aliases
  type Channel = String
  type Topic = Option[AnyRef]
  type Client = ActorRef
  type Provider = ActorRef

  // <2do> we should move the messages here

  // <2do> should include type and location
  case class ChildNode(actorRef: ActorRef, children: Set[ChildNode])

  //--- logging utilities - use this to avoid superfluous string interpolation
  // NOTE - these do not work per object (actor etc.), only per system
  def debug(msg: => String)(implicit log: LoggingAdapter): Unit = if (log.isDebugEnabled) log.debug(msg)
  def info(msg: => String)(implicit log: LoggingAdapter): Unit = if (log.isInfoEnabled) log.info(msg)
  def warning(msg: => String)(implicit log: LoggingAdapter): Unit = if (log.isWarningEnabled) log.warning(msg)
  def error(msg: => String)(implicit log: LoggingAdapter): Unit = if (log.isErrorEnabled) log.error(msg)

  // this is only the fallback
  implicit val timeout = Timeout(System.getProperty("race.timeout", "15").toInt seconds)

  trait AskFailure
  case object TimedOut extends AskFailure
  case object NotFound extends AskFailure
  case object InvalidResponse extends AskFailure

  // NOTE - the ask pattern uses temporary actors, i.e. the receiver cannot query the originator via sender
  // NOTE ALSO - the ask pattern does not support recursion, use only in a context that does not require round trips
  def askForResult[T](question: => Future[Any])(checkResponse: PartialFunction[Any, T])(implicit timeout: Timeout): T = {
    try {
      checkResponse(Await.result(question, timeout.duration))
    } catch {
      case t: TimeoutException => checkResponse(TimedOut)
    }
  }

  def waitForActor(aref: ActorRef)(fail: PartialFunction[AskFailure, Unit])(implicit timeout: Timeout): Option[ActorRef] = {
    askForResult(aref ? Identify(aref.path)) {
      case ActorIdentity(_, Some(`aref`)) =>
        Some(aref)
      case ActorIdentity(path: String, None) =>
        fail(NotFound)
        None
      case TimedOut =>
        fail(TimedOut)
        None
      case other =>
        fail(InvalidResponse)
        None
    }
  }
}