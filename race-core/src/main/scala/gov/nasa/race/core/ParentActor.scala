/*
 * Copyright (c) 2020, United States Government, as represented by the
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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{AskSupport, ask}
import com.typesafe.config.Config
import gov.nasa.race.core.Messages.{PingRaceActor, RaceActorTerminateFailed, RaceActorTerminateIgnored, RaceActorTerminated, TerminateRaceActor}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * interface that hides parent actor details from child actor creation context, which does not
  * have to be an actor but can be a construct such as a RaceRouteInfo
  *
  * Note this mostly factors out functions shared by Master and ParentRaceActor and hence is not
  * assuming the parent is a RaceActor. The children however are
  */
trait ParentActor extends Actor with ImplicitActorLogging with AskSupport {

  //--- basic actor interface
  def name: String
  def system: ActorSystem
  def self: ActorRef

  // we could also use ListMap but its lookup seems to be O(N), which would effectively limit
  // the number of children. ListMap also does not offer index access in O(1)
  protected[this] val actors = ArrayBuffer.empty[ActorMetaData]
  protected[this] val actorRefMap = mutable.HashMap.empty[ActorRef,ActorMetaData] // ActorRef.hashCode -> actors index

  def hasChildActors: Boolean = !actors.isEmpty
  def noMoreChildren: Boolean = actors.isEmpty

  //--- actor instantiation, addition and removal
  def actorOf(props: Props, name: String): ActorRef = system.actorOf(props,name)

  // and probably some more..

  def addChildActorRef (actorRef: ActorRef, actorConf: Config): Unit = {
    val actorData = new ActorMetaData(actorRef,actorConf)
    actors += actorData
    actorRefMap += (actorRef -> actorData)
    context.watch(actorRef)
  }

  def addChildActor(actorName: String, actorConf: Config, actorProps: Props): Unit = {
    addChildActorRef( actorOf(actorProps,actorName), actorConf)
  }

  def removeChildActorRef (actorRef: ActorRef): Unit = {
    actorRefMap.remove(actorRef) match {
      case Some(actorData) => {
        val idx = actors.indexOf(actorData)
        if (idx >= 0) actors.remove(idx)
      }
      case None => // ignore
    }
  }

  def stoppedChildActorRef (actorRef: ActorRef): Unit = {
    actorRefMap.get(actorRef) match {
      case Some(actorData) => actorData.isUnresponsive = true // this actor does not respond anymore
      case None => // ignore (or maybe warn)
    }
  }

  def isChildActorRef (actorRef: ActorRef): Boolean = actorRefMap.contains(actorRef)


  //--- internal methods to process children

  /**
    * BEWARE - only use this if the system is terminated
    */
  protected[this] def clear: Unit = {
    actors.clear
    actorRefMap.clear
  }

  protected[this] def askChild (actorRef: ActorRef, q: ()=>Any)(p: PartialFunction[Any,Boolean]): Boolean = {
    askForResult( actorRef ? q())(p)
  }
  protected[this] def askChildren (q: ()=>Any)(p: PartialFunction[Any,Boolean]): Boolean = {
    !actors.exists(e=> !askChild(e.actorRef, q)(p))
  }

  protected[this] def removePassingChildren (q: ()=>Any)(p: PartialFunction[Any,Boolean]): Unit = {
    var i = 0
    while (i < actors.length) {
      val ad = actors(i)
      if (askChild(ad.actorRef, q)(p)) {
        actors.remove(i)
        actorRefMap.remove(ad.actorRef)
      }
      i += 1
    }
  }

  protected[this] def processChildRef (actorRef: ActorRef)(f: ActorMetaData=>Unit): Unit = {
    actorRefMap.get(actorRef) match {
      case Some(actorData) => f(actorData)
      case None => // ignore
    }
  }

  protected[this] def processChildren (f: ActorMetaData=>Unit): Unit = {
    var i=0
    while (i<actors.length) { f(actors(i)); i += 1 }
  }

  protected[this] def processRespondingChildren (f: ActorMetaData=>Unit): Unit = {
    var i=0
    while (i<actors.length) {
      val actorData = actors(i)
      if (!actorData.isUnresponsive) f(actorData);
      i += 1
    }
  }

  protected[this] def sendToChildren (f: =>Any): Unit = {
    var i=0
    while (i<actors.length) {
      val actorData = actors(i)
      if (!actorData.isUnresponsive) {
        actorData.actorRef ! f
      }
      i += 1
    }
  }

  protected[this] def processChildrenReverse (f: ActorMetaData=>Unit): Unit = {
    var i = actors.length-1
    while (i >= 0) { f(actors(i)); i -= 1 }
  }

  //--- RaceActor specific message protocols

  /**
    * this is a parent function that only works for children that are RaceActors and hence
    * implement the termination message protocol
    */
  protected[this] def terminateAndRemoveRaceActors: Boolean = {
    processChildrenReverse { actorData =>
      val actorRef = actorData.actorRef
      if (!actorData.isUnresponsive) { // no use to terminate otherwise
        info(s"sending TerminateRaceActor to ${actorRef.path.name}")

        askForResult(actorRef ? TerminateRaceActor(self)) {
          case RaceActorTerminated => // all fine, actor did shut down
            info(s"got RaceActorTerminated from ${actorRef.path.name}")
            context.stop(actorRef) // stop it so that name becomes available again
            // note we still should remove the actorRef here since the Terminate is processed async
            removeChildActorRef(actorRef)

          //--- rejects
          case RaceActorTerminateIgnored =>
            info(s"got RaceActorTerminateIgnored from ${actorRef.path.name}")

          //--- failures
          case RaceActorTerminateFailed(reason) =>
            warning(s"RaceActorTerminate of ${actorRef.path.name} failed: $reason")
          case TimedOut =>
            warning(s"no TerminateRaceActor response from ${actorRef.path.name}")
          case other => // illegal response
            warning(s"got unknown TerminateRaceActor response from ${actorRef.path.name}")
        }
      }
    }

    actors.isEmpty
  }

  //--- ping processing

  protected[this] def pingActors (statsCollector: ActorRef): Unit = {
    processChildren { actorData =>
      // don't need to reset receiveNanos
      actorData.actorRef ! PingRaceActor(System.nanoTime,statsCollector)
    }
  }

  protected[this] def processPingResponse (childRef: ActorRef, latencyNanos: Long, msgCount: Long): Unit = {
    actorRefMap.get(childRef) match {
      case Some(actorData) => actorData.receivedNanos = System.nanoTime
      case None => // ignore (maybe we should warn)
    }
  }
}

