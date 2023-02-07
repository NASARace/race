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

package gov.nasa.race.core

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{ActorEventBus, Logging, SubchannelClassification}
import akka.util.Subclassification

import scala.collection.concurrent.{TrieMap, Map => CMap}

/**
 * the abstract bus interface used by RaceActor.
 * This is required to transparently access the bus from remote RaceActors
 */
trait BusInterface {
  def subscribe (subscriber: ActorRef, to: String): Boolean
  def unsubscribe (subscriber: ActorRef, from: String): Boolean
  def publish (msg: ChannelMessage): Unit
}

/**
 * the bus interface for remote actors, which cannot directly get a bus
 * via contructor or InitializeRaceActor message arguments since the Bus
 * is not serializable - remote actors have to publish to the bus through a
 * `connectorRef` proxy.
 */
case class RemoteBusInterface (val masterRef: ActorRef, val connectorRef: ActorRef) extends BusInterface {

  // note this sends to the master, which also processes subsequent RaceInitialized
  // messages. This guarantees that subscriptions during system initialization
  // have happened before the master starts the simulation run
  def subscribe (subscriber: ActorRef, toChannel: String): Boolean = {
    connectorRef ! RemoteSubscribe(subscriber, toChannel)
    true
  }

  def unsubscribe (subscriber: ActorRef, fromChannel: String): Boolean = {
    connectorRef ! RemoteUnsubscribe(subscriber,fromChannel)
    true
  }

  def publish (msg: ChannelMessage) = {
    connectorRef ! msg  // connectorRef is remote so msg will be serialized by Akka
  }
}

/**
 * the bus implementation of a live universe (master) system
 *
 * <2do> SubchannelClassification doesn't have a query mechanism for subscriptions (private),
 * so we might have to either create our own cache here or extend the trait
 */
class Bus (val system: ActorSystem) extends ActorEventBus with SubchannelClassification with BusInterface {
  type Event = ChannelMessage // (selector,payload,sender) - <2do> do we need a common event payload type?
  type Classifier = String

  implicit val log = Logging(system, this.getClass)

  // SubChannelClassification.subscriptions is unfortunately private
  protected val channelSubscriptions: CMap[Classifier,Set[ActorRef]] = TrieMap.empty

  protected def classify(event: Event): Classifier = event.channel

  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x.equals(y)

    def isSubclass(sub: Classifier, parent: Classifier) = {
      val l = parent.length-1
      if (parent.charAt(l) == '*') sub.regionMatches(0,parent,0,l) else sub.equals(parent)
    }
  }

  override def subscribe(sub: Subscriber, to: Classifier): Boolean = {
    info(s"$sub subscribed to $to")

    val actors = channelSubscriptions.getOrElse(to, Set[ActorRef]())
    channelSubscriptions += (to -> (actors + sub))
    super.subscribe(sub, to)
  }

  override def unsubscribe(sub: Subscriber, from: Classifier): Boolean = {
    info(s"$sub unsubscribed from $from")

    val actors = channelSubscriptions.getOrElse(from, Set.empty)
    if (actors.contains(sub)){
      channelSubscriptions += (from -> (actors - sub))
    }

    super.unsubscribe(sub, from)
  }

  def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber.tell(event, event.sender)
  }

  // for debugging
  def showChannelSubscriptions = {
    val s = new StringBuilder
    for (((channel,actors),i) <- channelSubscriptions.toSeq.sortBy(_._1).zipWithIndex){
      s ++= f" ${i+1}%3d: '$channel' subscribers:\n"
      for ((a,j) <- actors.zipWithIndex) {
        s ++= f"   ${j+1}%3d: ${a.path.name}\n"
      }
    }
    s.toString
  }
}
