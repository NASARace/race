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

/** a user ChannelMessage */
case class BusEvent (channel: String, msg: Any, sender: ActorRef) extends ChannelMessage

/** a system ChannelMessage */
// TODO - get rid of it so that we don't need another serializer
case class BusSysEvent (channel: String, msg: Any, sender: ActorRef) extends ChannelMessage with RaceSystemMessage


class BusEventSerializer  (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[BusEvent](system) {

  def serialize (e: BusEvent): Unit = {
    writeUTF(e.channel)
    writeActorRef(e.sender)
    writeEmbedded(e.msg)
  }

  def deserialize (): BusEvent = {
    val channel = readUTF()
    val sender = readActorRef()
    val msg = readEmbedded()

    BusEvent(channel,msg,sender)
  }
}