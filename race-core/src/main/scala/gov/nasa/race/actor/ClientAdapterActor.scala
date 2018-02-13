/*
 * Copyright (c) 2017, United States Government, as represented by the
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
import gov.nasa.race._
import gov.nasa.race.util.{SettableDIStream, SettableDOStream}

/**
  * AdapterActor that requests connections, i.e. requires a running external process which implements the
  * RACE adapter protocol
  */
class ClientAdapterActor(val config: Config) extends AdapterActor {
  import AdapterActor._

  override def processIncomingMsg (msgType: Short, packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader) = {
    val senderIp = packet.getAddress
    val senderPort = packet.getPort

    if (checkRemote(senderIp,senderPort)) {
      msgType match {
        case AcceptMsg =>
          id = readAccept(dis, header)
          remoteId = header.senderId
          info(s"received accept from $senderIp:$senderPort (serverId = $remoteId")

        case RejectMsg =>
          val reason = readReject(dis, header)
          info(s"received reject from $senderIp:$senderPort ($reason)")

        case StopMsg =>
          warning(s"received stop from $senderIp:$senderPort")
          readStop(dis, header)
          if (checkSender(senderIp, header.senderId)) stop

        case DataMsg => ifSome(reader) { r =>
          // make sure we don't allocate memory here for processing data messages - they might come at a high rate
          readHeader(dis, header)
          if (checkSender(senderIp, header.senderId)) {
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
        }
        case _ => warning(s"received unknown message type $msgType")
      }
    } else warning(s"ignoring message $msgType from unknown sender $senderIp:$senderPort")
  }

  override def onStartRaceActor(originator: ActorRef) = {
    importThread.start // start before we send the request
    sendRequest
    super.onStartRaceActor(originator)
  }

  //--- utility functions

  def writeRequest (os: SettableDOStream, flags: Int, schema: String, interval: Int): Int = {
    os.clear
    writeHeader(os, RequestMsg, NoFixedMsgLen, id)
    os.writeInt(flags)
    os.writeUTF(schema)
    os.writeLong(currentSimTimeMillis)
    os.writeInt(interval)
    val len = os.size
    setMsgLen(os,len.toShort)
    len
  }

  def sendRequest: Boolean = {
    val len = writeRequest(dos,flags,schema,dataInterval.toMillis.toInt)
    sendPacket(len)
  }

  // answer id
  def readAccept (is: DataInputStream, hdr: MsgHeader): Int = {
    readHeader(is, hdr)
    val remoteFlags = is.readInt
    val remoteInterval = is.readInt
    val id = is.readInt
    id
  }

  // answer reject reason
  def readReject (is: DataInputStream, hdr: MsgHeader): Int = {
    readHeader(is, hdr)
    is.readInt  // the reject reason
  }
}
