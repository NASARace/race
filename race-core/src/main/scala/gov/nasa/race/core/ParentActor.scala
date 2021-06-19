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
import akka.pattern.AskSupport
import akka.util.Timeout
import com.typesafe.config.Config

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * interface that hides parent actor details from child actor creation context, which does not
  * have to be an actor but can be a construct such as a RaceRouteInfo
  *
  * Note this mostly factors out functions shared by Master and ParentRaceActor and hence is not
  * assuming this is a RaceActor itself. The children however are
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

  def isChildActor (aref: ActorRef): Boolean = actorRefMap.contains(aref)

  //--- actor instantiation, addition and removal
  def actorOf(props: Props, name: String): ActorRef = context.actorOf(props,name)

  // check if this is an actor we know about (is part of our RAS)
  def isManaged (actorRef: ActorRef) = isChildActor(actorRef)

  // check if this is an actor we created
  def isSupervised (actorRef: ActorRef) = context.child(actorRef.path.name).isDefined
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
        context.unwatch(actorRef)
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
    actors.clear()
    actorRefMap.clear()
  }

  protected[this] def askChild (actorRef: ActorRef, q: (ActorMetaData)=>Any)(p: PartialFunction[Any,Boolean]): Boolean = {
    askForResult( actorRef ? q(actorRefMap(actorRef)))(p)
  }
  protected[this] def askChildren (q: (ActorMetaData)=>Any)(p: PartialFunction[Any,Boolean]): Boolean = {
    !actors.exists(e=> !askChild(e.actorRef, q)(p))
  }

  protected[this] def removePassingChildren (q: (ActorMetaData)=>Any)(p: PartialFunction[Any,Boolean]): Unit = {
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

  def nameOf( aRef: ActorRef): String = s"""'${aRef.path.name}'"""

  /**
    * this is a parent function that only works for children that are RaceActors and hence
    * implement the termination message protocol
    * Note that we only do a termination round trip to actors we supervise, otherwise we just remove
    * the actorRef from our list of children
    */
  protected[this] def terminateAndRemoveRaceActors (implicit timeout: Timeout): Boolean = {
    processChildrenReverse { actorData =>
      val actorRef = actorData.actorRef

      if (!actorData.isUnresponsive) { // no use to terminate otherwise
        info(s"sending TerminateRaceActor to ${nameOf(actorRef)}")

        askForResult(actorRef ? TerminateRaceActor(self)) {
          case RaceActorTerminated() => // all fine, actor did shut down
            info(s"got RaceActorTerminated from ${nameOf(actorRef)}")
            if (isSupervised(actorRef)) {
              context.stop(actorRef) // stop it so that name becomes available again
            }
            // note we still should remove the actorRef here since the Terminate is processed async
            removeChildActorRef(actorRef)

          //--- rejects
          case RaceActorTerminateReject() =>
            info(s"got RaceActorTerminateIgnored from ${nameOf(actorRef)}")

          //--- failures
          case RaceActorTerminateFailed(reason) =>
            warning(s"RaceActorTerminate of ${nameOf(actorRef)} failed: $reason")
          case TimedOut =>
            warning(s"no TerminateRaceActor response from ${nameOf(actorRef)}")
          case other => // illegal response
            warning(s"got unknown TerminateRaceActor response from ${nameOf(actorRef)}")
        }
      }
    }

    actors.isEmpty
  }

  //--- actor monitoring

  def registerChildren (registrar: ActorRef, queryPath: String): Unit = {
    processChildren { actorData =>
      actorData.actorRef ! RegisterRaceActor(registrar, queryPath)
    }
  }

  //--- debugging

  def printChildren(): Unit = {
    actors.foreach { actorData=>
      println(actorData.actorRef.path)
    }
  }
}

