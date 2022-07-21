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
}

/**
  * wrapper for something the DataClient wants to publish through its DataClientRaceActor
  */
case class PublishRaceData (data: Any)

/**
  * generic second tier (automatically created) actor that forwards received messages to a non-actor object
  */
class DataClientRaceActor(val dataClient: RaceDataClient, val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  /**
    * inform the dataClient of data received from the bus
    * override in case a specialized DataClientRaceActor has its own means of notifying its DataClient
    */
  def setClientData (newData: Any): Unit = {
    dataClient.receiveData.apply(newData)
  }

  /**
    * the generic implementation just forwards everything we receive from our 'read-from' channels to the client
    * override if concrete actor type has to consolidate data
    */
  override def handleMessage: Receive = {
    case e: BusEvent => setClientData(e)
    case msg: String => setClientData(msg)  // mostly for testing purposes

      // outbound (directly sent from dataClient) - publish to our write-to channel
    case PublishRaceData(data) => publish(data)
  }
}

/**
  * a RaceDataClient that gets periodic RaceTick notifications at configured intervals
  */
trait PeriodicRaceDataClient extends RaceDataClient {
  override protected def instantiateActor: DataClientRaceActor = new PeriodicDataClientRaceActor(this,config)

  def onRaceTick(): Unit
}

class PeriodicDataClientRaceActor(dataClient: PeriodicRaceDataClient, config: Config)
                                                extends DataClientRaceActor(dataClient,config) with PeriodicRaceActor {
  override def onRaceTick(): Unit = {
    dataClient.onRaceTick()
  }
}