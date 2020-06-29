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

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent

/**
  * an interface for a non-actor object that needs to obtain data from an associated second tier actor that
  * is effectively a bus probe. The DataClient instantiates this actor and hence could break encapsulation, so
  * care has to be taken to
  *
  * The DataClient context includes a ParentActor which is used to supervise
  */
trait RaceDataConsumer {
  val parent: ParentActor
  val config: Config
  val name: String

  protected val actorRef = createActor

  // can be overridden by concrete type if data consolidation is required from the actor
  protected def instantiateActor: DataConsumerRaceActor = new DataConsumerRaceActor(this,config)

  protected def createActor: ActorRef = {
    val aRef = parent.actorOf(Props(instantiateActor), name)
    parent.addChildActorRef(aRef,config)
    aRef
  }

  /**
    * to be provided by concrete type
    * NOTE - this method is directly called by an associated actor and hence has to provide appropriate synchronization
    * in case DataClient and DataClientRaceActor do not execute in the same thread
    */
  def setData (newData: Any): Unit
}

/**
  *
  */
class DataConsumerRaceActor(val dataConsumer: RaceDataConsumer, val config: Config) extends SubscribingRaceActor {

  def setData(newData: Any) = dataConsumer.setData(newData)

  /**
    * the generic implementation just forwards everything we receive from our 'read-from' channels to the client
    * override if concrete actor type has to consolidate data
    */
  override def handleMessage = {
    case BusEvent(_, data: Any, _) => setData(data)
    case msg: String => setData(msg)  // mostly for testing purposes
  }
}
