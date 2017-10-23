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
import gov.nasa.race.common.Status
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
    os.reset
    writeHeader(os, RequestMsg, NoFixedMsgLen, id)
    os.writeInt(flags)
    os.writeUTF(inType)
    os.writeUTF(outType)
    os.writeInt(interval)
    val len = os.size
    setMsgLen(os,len.toShort)
    len
  }

  def writeStop (os: SettableDOStream): Int = {
    os.reset
    writeHeader(os, StopMsg, StopLen, id)
  }

  def peekMsgType (is: SettableDIStream) = is.peekShort(0)

  def readHeader (is: DataInputStream, header: MsgHeader) = {
    header.msgType = is.readShort
    header.msgLen = is.readShort
    header.senderId = is.readInt
    header.epochMillis = is.readLong
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

  val importThread = ThreadUtils.daemon(readRemoteData)

  /**
    * msg acquisition thread function
    * this is executed concurrently to the rest of the code - beware of race conditions
    */
  def readRemoteData: Unit = {
    val maxFailures = config.getIntOrElse("max-failures", 5) // if we exceed, we stop reading

    val dis = new SettableDIStream(MaxMsgLen)
    val readPacket = new DatagramPacket(dis.getBuffer, dis.getCapacity)
    val header = new MsgHeader(0,0,0,0)

    val ret = loopWithExceptionLimit(maxFailures) (status == Status.Running) {
      socket.receive(readPacket)
      dis.reset
      val msgType = peekMsgType(dis)
      msgType match {
        case AcceptMsg => id = readAccept(dis,header)
        case RejectMsg => readReject(dis,header)
        case StopMsg =>
        case DataMsg => ifSome(reader) { r =>
          readHeader(dis,header)
          // TODO - check sender and out-of-order here
          val nDataRecords = dis.readShort
          if (nDataRecords > 0) {
            val list = r.read(dis,nDataRecords)
            list foreach publish
          }
        }
        case x => warning(s"received unknown message type $msgType")
      }
    }
    if (!ret) error("max failure threshold reached, terminating adapter read thread")
  }

  override def onStartRaceActor(originator: ActorRef) = {
    importThread.start // start before we send the request
    sendRequest
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    if (importThread.isAlive){
      importThread.interrupt
    }
    sendStop

    socket.close()

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case BusEvent(_, msg: Any, _) =>
      ifSome(writer) { w=>
        if (w.write(dos,msg)) sendPacket(dos.position)
      }
  }

  //--- utility functions

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
    info(s"ClientAdapter accepted by $remoteIpAddress (flags=$remoteFlags, interval=$remoteInterval) -> id= $id")
    id
  }

  // answer reject reason
  def readReject (is: DataInputStream, hdr: MsgHeader): Int = {
    readHeader(is, hdr)
    val reject = is.readInt
    info(s"ClientAdapter rejected by $remoteIpAddress (reason=$reject)")
    reject
  }

  def sendStop = {
    val len = writeStop(dos)
    sendPacket(len)
  }
}
