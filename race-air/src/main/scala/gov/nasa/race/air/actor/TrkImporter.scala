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
package gov.nasa.race.air.actor

import java.net.{DatagramPacket, InetAddress, MulticastSocket}

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.air.{ExtendedFlightPos, TrackedAircraft}
import gov.nasa.race.common.Status
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.util.{SettableDIStream, ThreadUtils}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

/**
  * actor that imports TRK messages received from a multicast group
  */
class TrkImporter (val config: Config) extends FilteringPublisher {
  val MaxMsgLen: Int = 1600

  //--- this is using multicast so we need a group ip address /port
  val groupAddr = InetAddress.getByName(config.getStringOrElse("group-address", "239.0.0.42"))
  val groupPort = config.getIntOrElse("group-port", 4242)

  //--- if interval == 0 we publish as soon as we get the packets
  val publishInterval = config.getFiniteDurationOrElse("interval", 0.milliseconds)
  var publishScheduler: Option[Cancellable] = None

  val socket = createSocket
  var receiverThread: Thread = ThreadUtils.daemon(runReceiver)

  def createSocket = {
    val sock = new MulticastSocket(groupPort)
    sock.setReuseAddress(true)
    sock
  }

  override def onStartRaceActor(originator: ActorRef) = {
    receiverThread.start
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    receiverThread.interrupt
    super.onTerminateRaceActor(originator)
  }

  def runReceiver: Unit = {
    try {
      socket.joinGroup(groupAddr)

      info(s"joined multicast $groupAddr")
      val tracks = new ArrayBuffer[TrackedAircraft](256)
      val buf = new Array[Byte](256)

      val dis = new SettableDIStream(MaxMsgLen)
      val packet = new DatagramPacket(dis.getBuffer, dis.getCapacity)

      while (status == Status.Running) {
        socket.receive(packet)
        println(s"@@ received ${packet.getLength} bytes")
        if (decodeMsg(dis,tracks,buf)){
          if (publishScheduler.isEmpty) {
            tracks.foreach(publishFiltered)
          }
        }
      }
    } finally {
      socket.leaveGroup(groupAddr)
      socket.close
    }
  }

  // this should be a Reader so that we can chose the concrete track type
  def decodeMsg(dis: SettableDIStream, tracks: ArrayBuffer[TrackedAircraft], buf: Array[Byte]): Boolean = {
    dis.clear
    tracks.clear

    if (dis.readByte != 'T' || dis.readByte != 'R' || dis.readByte != 'K'){ // magic header
      info("unknown message type")
      return false
    }

    val nTracks: Int = dis.readByte & 0xff
    println(s"@@ $nTracks tracks")

    var i = 0
    while (i < nTracks) {
      val idLen: Int = dis.readByte & 0xff
      if (dis.read(buf,0,idLen) != idLen) return false
      val id = new String(buf,0,idLen)
      println(s"@@ track $id")

      val date = new DateTime(dis.readLong)
      val lat = Degrees(dis.readDouble)
      val lon = Degrees(dis.readDouble)
      val alt = Meters(dis.readDouble)
      val hdg = Degrees(dis.readFloat)
      val spd = MetersPerSecond(dis.readFloat)
      val vr = MetersPerSecond(dis.readFloat)
      val phi = Degrees(dis.readFloat)
      val theta = Degrees(dis.readFloat)
      val status = dis.readInt

      val track = new ExtendedFlightPos(id,id, LatLonPos(lat,lon),alt, spd,hdg,vr, date,status, theta,phi,"?")
      tracks += track
      i += 1
    }

    true
  }

}
