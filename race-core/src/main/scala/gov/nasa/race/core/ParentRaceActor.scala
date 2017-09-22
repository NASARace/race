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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import com.typesafe.config.Config
import gov.nasa.race.core.Messages._

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag


/**
  * pure interface that hides parent actor details from child actor creation context
  */
trait ParentContext {
  def name: String
  def system: ActorSystem
  def self: ActorRef

  //--- actor instantiation
  def instantiateActor (actorName: String, actorConfig: Config): ActorRef
  def actorOf(props: Props, name: String): ActorRef

  // general instantiation
  def newInstance[T: ClassTag](clsName: String,
                               argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T]
  // and probably some more..

  def addChild (raRec: RaceActorRec): Unit
}

/**
  * a RaceActor that itself creates and manages a set of RaceActor children
  *
  * the main purpose of this trait is to keep a list of child actor infos (actorRef and config), and
  * to manage the state callbacks for them (init,start,termination)
  */
trait ParentRaceActor extends RaceActor with ParentContext {
  val children: ArrayBuffer[RaceActorRec] = new ArrayBuffer[RaceActorRec]

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    // TODO - shouldn't we delegate to super before forwarding to children?
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
    if (terminateChildActors) {
      super.onTerminateRaceActor(originator)
    } else false
  }
  // TODO - add onRestart and onPause forwarding

  def initializeChildActors (rc: RaceContext, actorConf: Config): Boolean = {
    // we can't use askChildren because we need a different message object for each of them
    !children.exists { e=> !askChild(e,InitializeRaceActor(rc,e.config),RaceActorInitialized) }
  }

  def startChildActors: Boolean = askChildren(StartRaceActor(self),RaceActorStarted)

  def terminateChildActors: Boolean = askChildrenReverse(TerminateRaceActor(self),RaceActorTerminated)

  def askChild (actorRef: ActorRef, question: Any, answer: Any): Boolean = {
    askForResult (actorRef ? question){
      case `answer` => true
      case TimedOut => warning(s"dependent actor timed out: ${actorRef.path.name}"); false
    }
  }
  def askChildren (question: Any, answer: Any): Boolean = !children.exists(!askChild(_,question,answer))

  def askChildrenReverse (question: Any, answer: Any): Boolean = {
    for ( cr <- children.reverseIterator ){
      if (!askChild(cr,question,answer)) return false
    }
    true
  }

  def actorOf(props: Props, name: String): ActorRef = context.actorOf(props,name)

  def addChild (raRec: RaceActorRec): Unit = children += raRec

  @inline def childActorRef (i: Int) = children(i).actorRef
}
