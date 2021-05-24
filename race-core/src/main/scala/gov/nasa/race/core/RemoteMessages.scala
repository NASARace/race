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
import gov.nasa.race.common.{AkkaSerializer, OATHash}
import gov.nasa.race.core.Messages.{ChannelMessage, RaceSystemMessage}

trait RemoteRaceSystemMessage extends RaceSystemMessage

//--- RAS lifetime control messages
// these are only exchanged between masters during remote actor creation
case class RemoteConnectionRequest (requestingMaster: ActorRef) extends RemoteRaceSystemMessage
case object RemoteConnectionAccept extends RemoteRaceSystemMessage
case object RemoteConnectionReject extends RemoteRaceSystemMessage
case class RemoteRaceTerminate (remoteMaster: ActorRef)  extends RemoteRaceSystemMessage // Master -> RemoteMaster

//--- messages to support remote bus subscribers/publishers, remoteActor -> BusConnector
case class RemoteSubscribe (actorRef: ActorRef, channel: Channel) extends RemoteRaceSystemMessage
case class RemoteUnsubscribe (actorRef: ActorRef, channel: Channel) extends RemoteRaceSystemMessage

case class RemotePublish (msg: ChannelMessage) extends RemoteRaceSystemMessage


class RemoteSystemMessageSerializer (system: ExtendedActorSystem) extends AkkaSerializer(system) {

  override def includeManifest: Boolean = true // include class manifests

  def serialize (aRef: ActorRef): Array[Byte] = {
    clear()
    writeActorRef(aRef)
    toByteArray
  }

  def serialize (aRef: ActorRef, channel: Channel): Array[Byte] = {
    clear()
    writeActorRef(aRef)
    writeUTF(channel.toString)
    toByteArray
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case RemoteConnectionRequest(aRef) => serialize(aRef)
      case RemoteRaceTerminate(aRef) => serialize(aRef)
      case RemoteConnectionAccept | RemoteConnectionReject => empty // nothing to serialize, the manifest is enough
      case RemoteSubscribe (aRef: ActorRef, channel: Channel) => serialize(aRef,channel)
      case RemoteUnsubscribe (aRef: ActorRef, channel: Channel) => serialize(aRef,channel)

    }
  }

  val RemoteConnectionRequest_ = classOf[RemoteConnectionRequest]
  val RemoteConnectionAccept_ = RemoteConnectionAccept.getClass
  val RemoteConnectionReject_ = RemoteConnectionReject.getClass
  val RemoteRaceTerminate_ = classOf[RemoteRaceTerminate]
  val RemoteSubscribe_ = classOf[RemoteSubscribe]
  val RemoteUnsubscribe_ = classOf[RemoteUnsubscribe]

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) =>
        setData(bytes)
        clazz match {
          case RemoteConnectionRequest_ => RemoteConnectionRequest( readActorRef())
          case RemoteConnectionAccept_ => RemoteConnectionAccept
          case RemoteConnectionReject_ => RemoteConnectionReject
          case RemoteRaceTerminate_ => RemoteRaceTerminate( readActorRef())
          case RemoteSubscribe_ => RemoteSubscribe( readActorRef(), readUTF())
          case RemoteUnsubscribe_ => RemoteUnsubscribe( readActorRef(), readUTF())
        }

      case None => null // no manifest?
    }
  }
}