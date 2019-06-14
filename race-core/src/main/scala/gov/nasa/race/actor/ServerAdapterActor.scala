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
import gov.nasa.race.loopWithExceptionLimit
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

  override def defaultOwnPort = DefaultServerPort
  override def defaultRemotePort = NoPort

  /**
    * msg acquisition thread function
    * NOTE this is executed concurrently to the rest of the code - beware of race conditions
    */
  override def processRemoteMessages: Unit = {
    info(s"import thread running")

    val dis = new SettableDIStream(MaxMsgLen)
    val readPacket = new DatagramPacket(dis.getBuffer, dis.getCapacity)
    val header = new MsgHeader(0,0,0,0)

    if (!loopWithExceptionLimit(maxFailures) (!terminate) { // request loop
      isConnected = false
      remotePort = NoPort
      remoteId = NoId

      if (waitForRequestMessage(readPacket,dis,header)) {
        remotePort = readPacket.getPort
        remoteId += 1
        isConnected = true

        sendAccept

        if (!loopWithExceptionLimit(maxFailures)(isConnected && !terminate) { // connection loop
          waitForConnectedMessage(readPacket,dis,header)
        }) error(s"max failure threshold reached, terminating connection to client $remoteIpAddress:$remotePort")
      }
    }) error("max failure threshold reached, terminating import thread")

    info("import thread terminated")
  }

  def waitForRequestMessage(packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader): Boolean = {
    dis.reset
    info(s"waiting for request from $remoteIpAddress on $ownIpAddress:$ownPort")

    socket.receive(packet)

    val msgType = peekMsgType(dis)
    val senderIp = packet.getAddress
    val senderPort = packet.getPort

    if (senderIp == remoteIpAddress) {
      if (msgType == RequestMsg) {
        if (processRequest(dis,header)) { // this does the payload content check
          isConnected = true
          remoteId += 1
          true // accept

        } else {
          warning(s"rejected request from $senderIp:$senderPort")
          false
        }
      } else {
        warning(s"ignoring non-request msg $msgType from ")
        false
      }
    } else {
      warning(s"ignoring msg from unknown client $senderIp:$senderPort")
      false
    }
  }

  /**
    * check connection request data from known remote
    */
  def processRequest (is: DataInputStream, hdr: MsgHeader): Boolean = {
    readHeader(is, hdr)

    val clientFlags = is.readInt
    val requestSchema = is.readUTF
    val requestTime = is.readLong // ? should we support setting the SimClock here?
    val requestInterval = is.readInt

    // for now we just check the schema
    if (requestSchema == schema){
      info(s"accepting request (${clientFlags.toHexString},$requestSchema,$requestInterval msec)")
      true
    } else {
      warning(s"request for unknown schema ignored: $requestSchema")
      false
    }
  }

  override def terminateConnection: Unit = {
    isConnected = false
  }

  override def onStartRaceActor(originator: ActorRef) = {
    importThread.start
    super.onStartRaceActor(originator)
  }

  def writeAccept (os: SettableDOStream, serverFlags: Int, simMillis: Long, interval: Int, clientId: Int): Int = {
    os.clear
    writeHeader(os, AcceptMsg, AcceptLen, id)
    os.writeInt(serverFlags)
    os.writeLong(simMillis)
    os.writeInt(interval)
    os.writeInt(clientId)
    os.size
  }

  def sendAccept: Boolean = {
    info(s"sending accept to client $remoteId at $remoteIpAddress:$remotePort")
    val len = writeAccept(dos,flags,currentSimTimeMillis,dataInterval.toMillis.toInt,remoteId)
    sendPacket(len)
  }
}
