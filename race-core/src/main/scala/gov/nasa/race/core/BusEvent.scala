/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import akka.actor.{ActorRef, ExtendedActorSystem}


/** a message type that can be published on a Bus channel */
trait ChannelMessage {
  def channel: String
  def msg: Any
  def sender: ActorRef
}

/**
  * a message sent through a bus channel
  *
  * NOTE - the 'msg' payload has to be a matchable type since it is used in the handleMessage PFs
  * (and also needs a serializer in case this BusEvent might get sent to a remote actor)
  */
case class BusEvent (channel: String, msg: Any, sender: ActorRef) extends ChannelMessage

/**
  * we need another type for system events posted to channels that are not matched in user event handlers
  */
case class BusSysEvent (channel: String, msg: Any, sender: ActorRef) extends ChannelMessage


//--- serializer support

trait ChannelMessageSerializer [T <: ChannelMessage] extends AkkaSerializer {
  def createChannelMessage (channel: String, msg: Any, sender: ActorRef): T

  def serialize (e: T): Unit = {
    writeUTF(e.channel)
    writeActorRef(e.sender)
    writeEmbedded(e.msg)
  }

  def deserialize (): T = {
    val channel = readUTF()
    val sender = readActorRef()
    val msg = readEmbeddedRef()
    createChannelMessage(channel,msg,sender)
  }
}

class BusEventSerializer  (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[BusEvent](system) with ChannelMessageSerializer[BusEvent] {
  def createChannelMessage (channel: String, msg: Any, sender: ActorRef) = BusEvent(channel,msg,sender)
}

class BusSysEventSerializer  (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[BusSysEvent](system) with ChannelMessageSerializer[BusSysEvent] {
  def createChannelMessage (channel: String, msg: Any, sender: ActorRef) = BusSysEvent(channel,msg,sender)
}