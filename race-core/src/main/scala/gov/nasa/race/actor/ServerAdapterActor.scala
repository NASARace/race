/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.actor

import java.io.DataInputStream
import java.net.DatagramPacket

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.ifSome
import gov.nasa.race.util.{SettableDIStream, SettableDOStream}

/**
  * AdapterActor that waits for incoming requests, i.e. has to be started before the external
  * process implementing the RACE adapter protocol
  *
  * This is a per-client actor, i.e. we need an actor for each external process that wants to
  * communicate with us. The rationale is that we need to be able to configure in/out channels
  * separately
  *
  * Note that apart from the initial handshake the data streams are similar to what the
  * ClientAdapterActor does - the external process periodically sends flight positions, the
  * actor sends back messages whenever it receives them on its read-from channel
  */
class ServerAdapterActor (val config: Config) extends AdapterActor {
  import AdapterActor._

  id = ServerId
  info(s"waiting for request from $remoteIpAddress:$remotePort")

  override def processIncomingMsg (msgType: Short, packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader) = {
    val senderIp = packet.getAddress
    val senderPort = packet.getPort

    if (checkRemote(senderIp, senderPort)) {
      msgType match {
        case RequestMsg =>
          if (!hasClient) {
            if (processRequest(dis, header)) {
              remoteId += 1
              sendAccept
            }
          } else warning(s"ignoring request, client $senderIp:$senderPort already connected")

        case _ =>
          if (hasClient) {
            processConnectedMsg(msgType, packet, dis, header)
          } else warning(s"ignoring message $msgType, client not connected")
      }
    } else warning(s"ignoring message $msgType from unknown sender: $senderIp:$senderPort")
  }

  def processConnectedMsg (msgType: Short, packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader) = {
    msgType match {
      case StopMsg =>
        warning(s"received stop from $remoteIpAddress:$remotePort")
        readStop(dis, header)
        stop

      case DataMsg => ifSome(reader) { r =>
        // make sure we don't allocate memory here for processing data messages - they might come at a high rate
        readHeader(dis, header)
        if (header.epochMillis > tLastData) {
          tLastData = header.epochMillis
          r.read(dis) match {
            case None =>
            case Some(list: Seq[_]) => if (flatten) list.foreach(publish) else publish(list)
            case Some(data) => publish(data)
          }
        } else {
          // ignore out-of-order packets
        }
      }
      case _ => warning(s"received unknown message type $msgType")
    }
  }

  def hasClient = remoteId != NoId

  override def onStartRaceActor(originator: ActorRef) = {
    importThread.start // start before we send the request
    super.onStartRaceActor(originator)
  }

  def processRequest (is: DataInputStream, hdr: MsgHeader): Boolean = {
    readHeader(is, hdr)

    val clientFlags = is.readInt
    val requestSchema = is.readUTF
    val requestTime = is.readLong
    val requestInterval = is.readInt

    // for now we just check the schema
    if (requestSchema == schema){
      info(s"accepting request ($clientFlags.toHexString,$requestSchema,$requestInterval msec)")
      true
    } else {
      warning(s"request for unknown schema ignored: $requestSchema")
      false
    }
  }

  def writeAccept (os: SettableDOStream, serverFlags: Int, interval: Int, clientId: Int): Int = {
    os.clear
    writeHeader(os, AcceptMsg, AcceptLen, id)
    os.writeInt(serverFlags)
    os.writeInt(interval)
    os.writeInt(clientId)
    os.size
  }

  def sendAccept: Boolean = {
    val len = writeAccept(dos,flags,dataInterval.toMillis.toInt,1)
    sendPacket(len)
  }
}
