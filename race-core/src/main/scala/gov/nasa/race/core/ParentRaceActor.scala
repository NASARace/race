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
package gov.nasa.race.core

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorInitializationException, ActorRef, OneForOneStrategy, Terminated}
import akka.pattern.ask
import com.typesafe.config.Config
import gov.nasa.race.core._

import scala.collection.mutable
import scala.concurrent.duration.DurationInt


/**
  * a RaceActor that itself creates and manages a set of RaceActor children
  *
  * the main purpose of this trait is to keep a list of child actor infos (actorRef and config), and
  * to manage the state callbacks for them (init,start,termination)
  */
trait ParentRaceActor extends RaceActor with ParentActor {

  override val supervisorStrategy = raceActorSystem.defaultSupervisorStrategy

  def handleParentSystemMessage: Receive = {
    case RaceActorStopped() => stoppedChildActorRef(sender())  // only parents receive these
    case Terminated(actorRef) => removeChildActorRef(actorRef) // Akka death watch event
  }

  override def handleSystemMessage: Receive = handleParentSystemMessage orElse super.handleSystemMessage

  override def handleRegisterRaceActor (registrar: ActorRef, parentQueryPath: String): Unit = {
    val ownQueryPath = parentQueryPath + '/' + name
    registrar ! RaceActorRegistered(ownQueryPath)
    registerChildren(registrar, ownQueryPath)
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    if (initializeChildActors(rc,actorConf)) {
      super.onInitializeRaceActor(rc, actorConf)
    } else false
  }

  override def onReInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    if (initializeChildActors(rc,actorConf)) {
      super.onReInitializeRaceActor(rc, actorConf)
    } else false
  }

  override def onStartRaceActor(originator: ActorRef) = {
    if (startChildActors) {
      super.onStartRaceActor(originator)
    } else false
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    if (terminateAndRemoveRaceActors) {
      super.onTerminateRaceActor(originator)
    } else false
  }
  // TODO - add onRestart and onPause forwarding

  def initializeChildActors (rc: RaceContext, parentConf: Config): Boolean = {
    askChildren((amd)=>InitializeRaceActor(rc,amd.config)){
      case RaceActorInitialized(caps) =>
        capabilities = capabilities.intersection(caps)
        true
      case other =>
        false
    }
  }

  def startChildActors: Boolean = {
    askChildren((_)=>StartRaceActor(self)){
      case RaceActorStarted() => true
      case other =>
        warning(s"unexpected StartRaceActor response from ${sender()}: $other")
        false
    }
  }

}
