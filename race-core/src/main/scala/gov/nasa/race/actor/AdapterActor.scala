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

import java.io.{DataInputStream, DataOutputStream}
import java.net.{DatagramPacket, DatagramSocket, InetAddress}

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ContinuousTimeRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.{SettableDIStream, SettableDOStream, ThreadUtils}

import scala.concurrent.duration._

class MsgHeader (var msgType: Int, var msgLen: Int, var senderId: Int, var epochMillis: Long)

object AdapterActor {
  // max datagram length (should be < MTU to avoid IP fragmentation)
  final val MaxMsgLen: Short = 1024

  final val RequestMsg: Short = 1
  final val AcceptMsg: Short  = 2
  final val RejectMsg: Short  = 3
  final val DataMsg: Short    = 4
  final val StopMsg: Short    = 5

  final val NoId: Int = -1

  final val NoFixedMsgLen: Short = 0
  final val HeaderLen: Short = 16
  final val AcceptLen: Short = 28 // HeaderLen + 12
  final val RejectLen: Short = 20 // HeaderLen + 4
  final val StopLen: Short   = HeaderLen
  // both Data and Request are variable length
}

/**
  * the common base for all AdapterActors
  */
trait AdapterActor extends PublishingRaceActor with SubscribingRaceActor with ContinuousTimeRaceActor {
  import AdapterActor._

  val reader: Option[DataStreamReader] = createReader
  val writer: Option[DataStreamWriter] = createWriter
  val flags: Int = 0 // not yet

  val ownIpAddress = InetAddress.getByName(config.getStringOrElse("own-ip-address", "localhost"))
  val ownPort = config.getIntOrElse("own-port", 50036)
  var socket = new DatagramSocket(ownPort, ownIpAddress)

  val flatten = config.getBooleanOrElse("flatten", true) // do we flatten reader data when publishing

  val dos = new SettableDOStream(MaxMsgLen)
  val packet = new DatagramPacket(dos.getBuffer, dos.getCapacity)
  var id = NoId


  // those are protected methods so that we can have hardwired reader/writer instances in subclasses
  protected def createReader: Option[DataStreamReader] = configurable[DataStreamReader]("reader")
  protected def createWriter: Option[DataStreamWriter] = configurable[DataStreamWriter]("writer")

  def sendPacketTo (packet: DatagramPacket, length: Int, ipAddress: InetAddress, port: Int): Boolean = {
    try {
      packet.setAddress(ipAddress)
      packet.setPort(port)
      packet.setLength(length)
      socket.send(packet)
      true
    } catch {
      case x: Throwable =>
        error(s"sending datagram failed: $x")
        false
    }
  }

  def writeHeader (os: DataOutputStream, msgType: Short, msgLen: Short, senderId: Int): Int = {
    os.writeShort(msgType)
    os.writeShort(msgLen)
    os.writeInt(senderId)
    os.writeLong(currentSimTimeMillis)
    os.size
  }

  def setMsgLen (os: SettableDOStream, msgLen: Short) = os.setShort(2,msgLen)

  def writeRequest (os: SettableDOStream, flags: Int, inType: String, outType: String, interval: Int): Int = {
    os.clear
    writeHeader(os, RequestMsg, NoFixedMsgLen, id)
    os.writeInt(flags)
    os.writeUTF(inType)
    os.writeUTF(outType)
    os.writeLong(currentSimTimeMillis)
    os.writeInt(interval)
    val len = os.size
    setMsgLen(os,len.toShort)
    len
  }

  def writeStop (os: SettableDOStream): Int = {
    os.clear
    writeHeader(os, StopMsg, StopLen, id)
  }

  def peekMsgType (is: SettableDIStream) = is.peekShort(0)

  def readHeader (is: DataInputStream, header: MsgHeader) = {
    header.msgType = is.readShort
    header.msgLen = is.readShort
    header.senderId = is.readInt
    header.epochMillis = is.readLong
  }

  def readStop (is: DataInputStream, hdr: MsgHeader): Int = {
    readHeader(is, hdr)
    hdr.senderId
  }
}

/**
  * AdapterActor that requests connections, i.e. requires a running external process which implements the
  * RACE adapter protocol
  */
class ClientAdapterActor(val config: Config) extends AdapterActor {
  import AdapterActor._

  // this is the interval at which we want the remote to send us data
  val dataInterval = config.getFiniteDurationOrElse("data-interval", 2000.milliseconds)

  // remote address and port we communicate with
  val remoteIpAddress = InetAddress.getByName(config.getStringOrElse("remote-ip-address", "localhost"))
  val remotePort = config.getIntOrElse("remote-port", 50037)
  var serverId = NoId
  val importThread = ThreadUtils.daemon(readRemoteData)
  var terminate = false
  var tLastData: Long = 0 // timestamp of last processed data message, to detect out-of-order messages

  /**
    * msg acquisition thread function
    * this is executed concurrently to the rest of the code - beware of race conditions
    */
  def readRemoteData: Unit = {
    val maxFailures = config.getIntOrElse("max-failures", 5) // if we exceed, we stop reading
    val dis = new SettableDIStream(MaxMsgLen)
    val readPacket = new DatagramPacket(dis.getBuffer, dis.getCapacity)
    val header = new MsgHeader(0,0,0,0)

    val ret = loopWithExceptionLimit(maxFailures) (!terminate) {
      socket.receive(readPacket)  // this blocks
      dis.reset
      processIncomingMsg(peekMsgType(dis),readPacket,dis,header)
    }
    if (!ret) error("max failure threshold reached, terminating adapter read thread")
    else info("import thread terminated normally")
  }

  def processIncomingMsg (msgType: Short, packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader) = {
    val senderIp = packet.getAddress
    val senderPort = packet.getPort

    msgType match {
      case AcceptMsg =>
        id = readAccept(dis,header)
        serverId = header.senderId
        info(s"received accept from $senderIp:$senderPort (serverId = $serverId")

      case RejectMsg =>
        val reason = readReject(dis,header)
        info(s"received reject from $senderIp:$senderPort ($reason)")

      case StopMsg =>
        warning(s"received stop from $senderIp:$senderPort")
        readStop(dis,header)
        if (checkSender(senderIp,header.senderId)) stop

      case DataMsg => ifSome(reader) { r =>
        // make sure we don't allocate memory here for processing data messages - they might come at a high rate
        readHeader(dis,header)
        if (checkSender(senderIp,header.senderId)) {
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
      case x => warning(s"received unknown message type $msgType")
    }
  }

  override def onStartRaceActor(originator: ActorRef) = {
    importThread.start // start before we send the request
    sendRequest
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    if (serverId != NoId) sendStop
    stop
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case BusEvent(_, msg: Any, _) =>
      if (serverId != NoId) {   // are we connected
        ifSome(writer) { w =>   // do we have a writer
          dos.clear
          dos.setPosition(HeaderLen) // we fill in the header once we know the writer handled this message
          val len = w.write(dos, msg)
          if (len > 0) {
            info(s"sending data message with $len bytes")
            dos.setPosition(0)
            writeHeader(dos, DataMsg, len.toShort, id)
            dos.setPosition(len)
            sendPacket(len)
          }
        }
      }
  }

  //--- utility functions

  def checkSender (addr: InetAddress, id: Int) = {
    id == serverId && addr.equals(remoteIpAddress)
  }

  def stop = {
    terminate = true
    serverId = NoId

    repeatUpTo(5)(importThread.isAlive){
      importThread.interrupt
      Thread.`yield`
    }
    socket.close
    info("stopped")
  }

  def sendPacket(len: Int): Boolean = sendPacketTo(packet,len,remoteIpAddress,remotePort)

  def sendRequest: Boolean = {
    val inType = withSomeOrElse(reader,""){_.schema }
    val outType = withSomeOrElse(writer, ""){_.schema}
    val len = writeRequest(dos,flags,inType,outType,dataInterval.toMillis.toInt)
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

  def sendStop = {
    val len = writeStop(dos)
    sendPacket(len)
  }
}
