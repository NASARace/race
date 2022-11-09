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

import akka.actor.Actor.Receive
import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * an interface for a non-actor object that needs to obtain data from an associated second tier actor that
  * is essentially a bus interface. The DataClient instantiates this actor and hence could break encapsulation, so
  * care has to be taken to avoid data races inside of setData() implementations
  */
trait RaceDataClient extends ConfigLoggable {
  val parent: ParentActor
  val config: Config
  val name: String

  protected val actorRef = createActor

  // can be overridden by concrete type if data consolidation is required from the actor
  protected def instantiateActor: DataClientRaceActor = new DataClientRaceActor(this,config)

  protected def createActor: ActorRef = {
    val aRef = parent.actorOf(Props(instantiateActor), name)
    parent.addChildActorRef(aRef,config)
    aRef
  }

  /**
    * to be overridden by concrete type. We only provide an empty implementation here in case we have several
    * RaceDataClient traits that need to chain their receiveData PFs.
    *
    * Override handleDataClientMatchError() in case unmatched messages should not just be ignored
    *
    * NOTE - this partial function is directly called by an associated actor and hence has to provide appropriate synchronization
    * in case DataClient and DataClientRaceActor do not execute in the same thread. Since we don't know anything about the
    * DataClient execution context here (might not be an actor) we can't avoid synchronization, hence any processing
    * should be kept at a minimum
    */
  def receiveData: Receive = {
    case msg => handleDataClientMatchError(msg)
  }

  def handleDataClientMatchError(a:Any): Unit = {
    info(s"ignoring unmatched DataClient message: $a")
  }

  def publishData(data: Any): Unit = {
    actorRef ! PublishRaceData(data)
  }

  def defaultTickInterval: FiniteDuration = 0.seconds  // no periodic notification unless config has a race-tick

  // function to be periodically invoked if config explicitly specifies a tick-interval
  def onRaceTick(): Unit = {}
}

/**
 * a RaceDataClient that pipes all data updates through a single actor to avoid race conditions when processing the data
 */
trait PipedRaceDataClient extends RaceDataClient {

  def getActorRef: ActorRef

  override def instantiateActor: DataClientRaceActor = new PipedDataClientRaceActor( getActorRef, this, config)
}

/**
 * wrapper for a message to be sent to an actor that owns a RaceDataClient: bus -> parentActor(DC)
 */
case class RaceDataClientMessage (dc: RaceDataClient, msg: Any)

/**
  * wrapper for something the DataClient wants to publish through its DataClientRaceActor: DC -> bus
  */
case class PublishRaceData (data: Any)

/**
  * generic second tier (automatically created) actor that forwards received messages to a non-actor object
  */
class DataClientRaceActor (val dataClient: RaceDataClient, val config: Config) extends SubscribingRaceActor with PublishingRaceActor with PeriodicRaceActor {

  override def defaultTickInterval: FiniteDuration = dataClient.defaultTickInterval

  /**
    * inform the dataClient of data received from the bus
    * override in case a specialized DataClientRaceActor has its own means of notifying its DataClient
    */
  protected def setClientData (newData: Any): Unit = {
    dataClient.receiveData( newData)
  }

  override def onRaceTick(): Unit = {
    dataClient.onRaceTick()
  }

  /**
    * the generic implementation just forwards everything we receive from our 'read-from' channels to the client
    * override if concrete actor type has to consolidate data
    *
    * note that if the client wants to publish something as a BusEvent this needs a sender and the configured channel,
    * hence we have to relay through the DataClientRaceActor
    */
  override def handleMessage: Receive = {
    case r:RaceSystemMessage => handleSystemMessage.apply(r) // make sure we don't forward system messages
    case PublishRaceData(data) => publish(data) // client wants to publish a BusEvent to our configured write-to channel
    case msg: Any => setClientData(msg)  // note that we don't unwrap BusEvents so that data clients see the channel and sender
  }
}

/**
 * a DataClientRaceActor that pipes dataClient notifications through a provided actorRef
 * use to transfer data processing into a single actor to avoid race conditions
 */
class PipedDataClientRaceActor (val actorRef: ActorRef, dataClient: RaceDataClient, config: Config) extends DataClientRaceActor(dataClient, config) {
  override protected def setClientData (newData: Any): Unit = {
    actorRef ! RaceDataClientMessage( dataClient, newData)
  }

  override def handleRaceTick: Receive = {
    case RaceTick => actorRef ! RaceDataClientMessage( dataClient, RaceTick)
  }
}

/**
 * a RaceActor that is used to pipe processing of data client updates to avoid race conditions
 */
trait DataClientExecutor extends RaceActor {
  def handleDataClientMessage: Receive = {
    case RaceDataClientMessage(dc,RaceTick) => dc.onRaceTick()
    case RaceDataClientMessage(dc,msg) => dc.receiveData(msg)
  }
}
