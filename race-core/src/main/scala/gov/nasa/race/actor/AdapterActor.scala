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
import gov.nasa.race.common.{DataStreamReader, DataStreamWriter}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, RacePauseRequest, RaceResumeRequest}
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
  final val PauseMsg: Short   = 6
  final val ResumeMsg: Short  = 7

  final val NoId: Int = -1
  final val ServerId: Int = 0

  final val NoPort: Int = -1
  final val DefaultServerPort: Int = 50036
  final val DefaultClientPort: Int = 50037

  final val NoFixedMsgLen: Short = 0
  final val HeaderLen: Short = 16
  final val AcceptLen: Short = 36 // HeaderLen + 20
  final val RejectLen: Short = 20 // HeaderLen + 4
  final val StopLen: Short   = HeaderLen
  final val PauseLen: Short  = HeaderLen
  final val ResumeLen: Short = HeaderLen
  // both Data and Request are variable length
}

/**
  * the common base for all AdapterActors
  *
  * note that AdapterActor only supports point-to-point connections. If we run this as a client
  * (external program started first) we have to specify both remote-ip-address and remote-port.
  * If this is a server (RACE started first) then we still need a remote-ip-address but set
  * the remote-port once we accept a connection
  */
trait AdapterActor extends PublishingRaceActor with SubscribingRaceActor with ContinuousTimeRaceActor {
  import AdapterActor._

  val schema = config.getString("schema")
  val reader: Option[DataStreamReader] = createReader
  val writer: Option[DataStreamWriter] = createWriter
  val flags: Int = 0 // not yet

  val soTimeout = config.getFiniteDurationOrElse("socket-timeout", 5.seconds)

  // this is the interval at which we want the remote to send us data
  val dataInterval = config.getFiniteDurationOrElse("data-interval", 1000.milliseconds)

  val ownIpAddress = InetAddress.getByName(config.getStringOrElse("own-ip-address", "localhost"))
  val ownPort = config.getIntOrElse("own-port", defaultOwnPort)

  val remoteIpAddress = InetAddress.getByName(config.getStringOrElse("remote-ip-address", "localhost"))
  var remotePort = config.getIntOrElse("remote-port", defaultRemotePort)

  var socket = new DatagramSocket(ownPort, ownIpAddress)

  val importThread = ThreadUtils.daemon(processRemoteMessages)
  var terminate = false // terminate the socket thread
  val maxFailures = config.getIntOrElse("max-failures", 5) // if we exceed, we stop the importThread

  val flatten = config.getBooleanOrElse("flatten", true) // do we flatten reader data when publishing

  val dos = new SettableDOStream(MaxMsgLen)
  val packet = new DatagramPacket(dos.getBuffer, dos.getCapacity)

  var isConnected = false // our connection status
  var tLastData: Long = 0 // timestamp of last processed data message, to detect out-of-order messages
  var id = NoId
  var remoteId = NoId

  ifSome(reader) { checkSchemaCompliance }
  ifSome(writer) { checkSchemaCompliance }

  protected def defaultOwnPort: Int
  protected def defaultRemotePort: Int

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

  def sendPacket(len: Int): Boolean = {
    (remotePort != NoPort) && sendPacketTo(packet,len,remoteIpAddress,remotePort)
  }

  def writeHeader (os: DataOutputStream, msgType: Short, msgLen: Short, senderId: Int): Int = {
    os.writeShort(msgType)
    os.writeShort(msgLen)
    os.writeInt(senderId)
    os.writeLong(currentSimTimeMillis)
    os.size
  }

  def setMsgLen (os: SettableDOStream, msgLen: Short) = os.setShort(2,msgLen)

  def writeHeaderOnlyMsg(os: SettableDOStream, msgType: Short): Int = {
    os.clear
    writeHeader(os, msgType, HeaderLen, id)
  }

  def writeStop (os: SettableDOStream): Int = writeHeaderOnlyMsg(os,StopMsg)

  def writePause (os: SettableDOStream): Int = writeHeaderOnlyMsg(os,PauseMsg)

  def writeResume (os: SettableDOStream): Int = writeHeaderOnlyMsg(os,ResumeMsg)

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

  /**
  * msg acquisition thread function
  * NOTE this is executed concurrently to the rest of the code - beware of race conditions
  */
  def processRemoteMessages: Unit

  /**
    * messages we process while being connected (regardless of server or client role)
    */
  def waitForConnectedMessage(packet: DatagramPacket, dis: SettableDIStream, header: MsgHeader) = {

    dis.reset
    socket.receive(packet)

    peekMsgType(dis) match {
      case StopMsg =>
        info(s"received stop from $remoteIpAddress:$remotePort")
        readStop(dis, header)
        terminateConnection

      case PauseMsg =>
        info(s"received pause from $remoteIpAddress:$remotePort")
        if (raceActorSystem.isRunning) master ! RacePauseRequest

      case ResumeMsg =>
        info(s"received resume from $remoteIpAddress:$remotePort")
        if (raceActorSystem.isPaused) master ! RaceResumeRequest

      case DataMsg => ifSome(reader) { r =>
        if (raceActorSystem.isRunning) {
          // make sure we don't allocate memory here for processing data messages - they might come at a high rate
          readHeader(dis, header)
          val tHeader = header.epochMillis
          if (tHeader >= tLastData) {
            tLastData = tHeader
            r.read(dis) match {
              case None =>
              case Some(list: Seq[_]) =>
                if (flatten) list.foreach(publish) else publish(list)
              case Some(data) => publish(data)
            }
          } else {
            warning(s"received out-of-order data message (dt=${tHeader - tLastData} msec)")
          }
        }
      }

      case msgType => warning(s"ignoring message $msgType from $remoteIpAddress:$remotePort")
    }
  }

  def checkSender (addr: InetAddress, id: Int) = {
    id == remoteId && addr.equals(remoteIpAddress)
  }

  def checkRemote (addr: InetAddress, port: Int): Boolean = (addr == remoteIpAddress) && (port == remotePort)

  def sendStop = sendPacket(writeStop(dos))

  def sendPause = sendPacket(writePause(dos))

  def sendResume = sendPacket(writeResume(dos))

  def terminateConnection: Unit

  override def onTerminateRaceActor(originator: ActorRef) = {
    if (isConnected) sendStop

    isConnected = false
    terminate = true
    remoteId = NoId

    repeatUpTo(5)(importThread.isAlive){
      importThread.interrupt
      Thread.`yield`
    }
    socket.close

    super.onTerminateRaceActor(originator)
  }

  override def onPauseRaceActor (originator: ActorRef): Boolean = {
    info("sending pause message")
    sendPause
    super.onPauseRaceActor(originator)
  }

  override def onResumeRaceActor (originator: ActorRef): Boolean = {
    info("sending resume message")
    sendResume
    super.onResumeRaceActor(originator)
  }


  override def handleMessage = {
    case BusEvent(_, msg: Any, _) =>
      if (isConnected) {
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
