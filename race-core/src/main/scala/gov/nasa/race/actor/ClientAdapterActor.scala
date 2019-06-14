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
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.{SettableDIStream, SettableDOStream}

import scala.concurrent.duration._

/**
  * AdapterActor that requests connections, i.e. requires a running external process which implements the
  * RACE adapter protocol
  */
class ClientAdapterActor(val config: Config) extends AdapterActor {
  import AdapterActor._

  override def defaultOwnPort = DefaultClientPort
  override def defaultRemotePort = DefaultServerPort

  /**
    * this is the data acquisition thread function - beware of race conditions
    *
    * note - connection request is done sync from the actor before we start this thread
    */
  override def processRemoteMessages: Unit = {
    info(s"import thread running")

    val dis = new SettableDIStream(MaxMsgLen)
    val readPacket = new DatagramPacket(dis.getBuffer, dis.getCapacity)
    val header = new MsgHeader(0,0,0,0)

    if (!loopWithExceptionLimit(maxFailures)(!terminate) { // connection loop
      waitForConnectedMessage(readPacket,dis,header)
    }) error(s"max failure threshold reached, terminating connection to server $remoteIpAddress:$remotePort")

    info("import thread terminated")
  }

  def waitForRequestResponse (timout: FiniteDuration): Boolean = {
    val dis = new SettableDIStream(MaxMsgLen)
    val packet = new DatagramPacket(dis.getBuffer, dis.getCapacity)
    val header = new MsgHeader(0,0,0,0)
    info(s"waiting for request response from $remoteIpAddress:$remotePort")

    socket.setSoTimeout(soTimeout.toMillis.toInt)
    try {
      socket.receive(packet) // this blocks
      socket.setSoTimeout(0) // disable timeout for connection

      val senderIp = packet.getAddress
      val senderPort = packet.getPort

      if (senderIp == remoteIpAddress || sender() == remotePort) {
        peekMsgType(dis) match {
          case AcceptMsg =>
            if (processAcceptMsg(dis, header)){
              isConnected = true
              remoteId = header.senderId
              true
            } else {
              error(s"accept from $remoteIpAddress:$remotePort failed")
              false
            }

          case RejectMsg =>
            val reason = readReject(dis, header)
            warning(s"got reject from $senderIp:$senderPort : ($reason)")
            false

          case msgType =>
            warning(s"ignoring non-request response $msgType from $senderIp:$senderPort")
            false
        }

      } else {
        warning(s"ignoring message from unknown $senderIp:$senderPort")
        false
      }
    } catch {
      case x: Throwable =>
        error(s"exception waiting for response from server $remoteIpAddress:$remotePort: $x")
        false
    }
  }

  /**
    * process payload of accept message
    */
  def processAcceptMsg (dis: SettableDIStream, header: MsgHeader): Boolean = {
    id = readAccept(dis, header)
    info(s"got accept from $remoteIpAddress:$remotePort (serverId = $remoteId, ownId = $id)")
    true
  }

  override def onStartRaceActor(originator: ActorRef) = {
    sendRequest
    if (waitForRequestResponse(startTimeout)) {
      importThread.start
      super.onStartRaceActor(originator)

    } else {
      isOptional && super.onStartRaceActor(originator)
    }
  }

  //--- utility functions

  override def terminateConnection: Unit = {
    isConnected = false
    terminate = true  // we don't try to reconnect
  }

  def writeRequest (os: SettableDOStream, flags: Int, schema: String, interval: Int): Int = {
    os.clear
    writeHeader(os, RequestMsg, NoFixedMsgLen, id)
    os.writeInt(flags)
    os.writeUTF(schema)
    os.writeLong(currentSimTimeMillis) // tell the native side what our sim time is
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
    val remoteSimTimeMillis = is.readLong // TODO check if this is the time we requested
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
