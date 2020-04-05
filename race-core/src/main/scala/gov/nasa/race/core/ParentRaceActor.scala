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

import akka.actor.ActorRef
import akka.pattern.ask
import com.typesafe.config.Config
import gov.nasa.race.core.Messages._


/**
  * a RaceActor that itself creates and manages a set of RaceActor children
  *
  * the main purpose of this trait is to keep a list of child actor infos (actorRef and config), and
  * to manage the state callbacks for them (init,start,termination)
  */
trait ParentRaceActor extends RaceActor with ParentActor {

  override def handleSystemMessage: Receive = {
    case RaceActorStopped => stoppedChildActorRef(sender)  // only parents receive these
    case msg: PingRaceActorResponse => handlePingRaceActorResponse(sender, msg)
    case otherMsg => super.handleSystemMessage(otherMsg)
  }

  // this is the exception from the rule that handleXX only call the overridable onXX methods
  // since we normally do not want actors to do anything but to instantly return the message
  override def handlePingRaceActor (originator: ActorRef, sentNanos: Long, statsCollector: ActorRef) = {
    super.handlePingRaceActor(originator,sentNanos,statsCollector) // respond yourself first

    // TODO - this needs to check unresponsive children !
    processRespondingChildren { actorData =>
      val actorRef = actorData.actorRef
      actorRef ! PingRaceActor(System.nanoTime, statsCollector)
    }
  }

  def handlePingRaceActorResponse (actorRef: ActorRef, msg: PingRaceActorResponse): Unit = {
    processChildRef(actorRef) { actorData=>
      actorData.receivedNanos = msg.receivedNanos
    }
  }

  override def handleShowRaceActor (originator: ActorRef, sentNanos: Long): Unit = {
    @inline def _indent (lvl: Int): Unit = {
      var i=0
      while (i < lvl) { print("  "); i+= 1 }
    }

    val latency: Long = System.nanoTime - sentNanos
    _indent(level)
    println(f"${name}%50s : $latency%6d,  $nMsgs%8d")

    actors.foreach { actorData =>
      val actorRef = actorData.actorRef
      askForResult(actorRef ? ShowRaceActor(System.nanoTime)) {
        case ShowRaceActorResponse => // all Ok, next
        case TimedOut =>
          _indent(level+1)
          println(f"${actorRef.path.name}%50s : UNRESPONSIVE")
        case other =>
          _indent(level+1)
          println(f"${actorRef.path.name}%50s : wrong response ($other)")
      }
    }

    originator ! ShowRaceActorResponse
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

  def initializeChildActors (rc: RaceContext, actorConf: Config): Boolean = {
    askChildren(()=>InitializeRaceActor(rc,actorConf)){
      case RaceActorInitialized(caps) =>
        capabilities = capabilities.intersection(caps)
        true
      case other =>
        false
    }
  }

  def startChildActors: Boolean = {
    askChildren(()=>StartRaceActor(self)){
      case RaceActorStarted => true
      case _ => false
    }
  }

}
