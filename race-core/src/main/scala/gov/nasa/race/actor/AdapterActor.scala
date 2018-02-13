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
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ContinuousTimeRaceActor, PublishingRaceActor, RaceException, SubscribingRaceActor}
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
  final val ServerId: Int = 0

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

  val schema = config.getString("schema")
  val reader: Option[DataStreamReader] = createReader
  val writer: Option[DataStreamWriter] = createWriter
  val flags: Int = 0 // not yet

  // this is the interval at which we want the remote to send us data
  val dataInterval = config.getFiniteDurationOrElse("data-interval", 2000.milliseconds)

  val ownIpAddress = InetAddress.getByName(config.getStringOrElse("own-ip-address", "localhost"))
  val ownPort = config.getIntOrElse("own-port", 50036)

  val remoteIpAddress = InetAddress.getByName(config.getStringOrElse("remote-ip-address", "localhost"))
  val remotePort = config.getIntOrElse("remote-port", 50037)

  var socket = new DatagramSocket(ownPort, ownIpAddress)

  val importThread = ThreadUtils.daemon(readRemoteMsg)
  var terminate = false // terminate the socket thread

  val flatten = config.getBooleanOrElse("flatten", true) // do we flatten reader data when publishing

  val dos = new SettableDOStream(MaxMsgLen)
  val packet = new DatagramPacket(dos.getBuffer, dos.getCapacity)

  var tLastData: Long = 0 // timestamp of last processed data message, to detect out-of-order messages

  var id = NoId
  var remoteId = NoId

  ifSome(reader) { checkSchemaCompliance }
  ifSome(writer) { checkSchemaCompliance }

  // those are protected methods so that we can have hardwired reader/writer instances in subclasses
  protected def createReader: Option[DataStreamReader] = configurable[DataStreamReader]("reader")
  protected def createWriter: Option[DataStreamWriter] = configurable[DataStreamWriter]("writer")

  protected def checkSchemaCompliance (si: SchemaImplementor): Unit = {
    if (!si.compliesWith(schema)) throw new RaceException(s"${si.getClass.getName} does not implement $schema")
  }

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

  def sendPacket(len: Int): Boolean = sendPacketTo(packet,len,remoteIpAddress,remotePort)

  def writeHeader (os: DataOutputStream, msgType: Short, msgLen: Short, senderId: Int): Int = {
    os.writeShort(msgType)
    os.writeShort(msgLen)
    os.writeInt(senderId)
    os.writeLong(currentSimTimeMillis)
    os.size
  }

  def setMsgLen (os: SettableDOStream, msgLen: Short) = os.setShort(2,msgLen)

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

  // to be provided by concrete clqss
  def processIncomingMsg (msgType: Short, packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader): Unit

  /**
  * msg acquisition thread function
  * NOTE this is executed concurrently to the rest of the code - beware of race conditions
  */
  def readRemoteMsg: Unit = {
    val maxFailures = config.getIntOrElse("max-failures", 5) // if we exceed, we stop reading
    val dis = new SettableDIStream(MaxMsgLen)
    val readPacket = new DatagramPacket(dis.getBuffer, dis.getCapacity)
    val header = new MsgHeader(0,0,0,0)

    if (loopWithExceptionLimit(maxFailures) (!terminate) {
      socket.receive(readPacket)  // this blocks
      dis.reset
      processIncomingMsg(peekMsgType(dis),readPacket,dis,header)
    }) info("import thread terminated normally")
    else error("max failure threshold reached, terminating adapter read thread")
  }

  def checkSender (addr: InetAddress, id: Int) = {
    id == remoteId && addr.equals(remoteIpAddress)
  }

  def checkRemote (addr: InetAddress, port: Int): Boolean = (addr == remoteIpAddress) && (port == remotePort)

  def sendStop = {
    val len = writeStop(dos)
    sendPacket(len)
  }

  def stop = {
    terminate = true
    remoteId = NoId

    repeatUpTo(5)(importThread.isAlive){
      importThread.interrupt
      Thread.`yield`
    }
    socket.close
    info("stopped")
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    if (remoteId != NoId) sendStop
    stop
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case BusEvent(_, msg: Any, _) =>
      if (remoteId != NoId) {   // are we connected
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
}
