/*
 * Copyright (c) 2016, United States Government, as represented by the
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

/*
 * Copyright (c) 2016, United States Government, as represented by the 
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

package gov.nasa.race.actors.bridges.xplane

import java.net.{DatagramPacket, DatagramSocket, InetAddress}

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.actors.bridges.xplane.XPlaneCodec.RPOS
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core._
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.data._
import gov.nasa.race.data.{FlightPos, LatLonPos, Positionable}
import squants.motion.{Knots, MetersPerSecond}
import squants.space.{Degrees, Feet, Meters, NauticalMiles}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

import scala.annotation.tailrec

/**
  * a bridge for the X-Plane flight simulator
  */
class XPlaneActor (val config: Config) extends PublishingRaceActor
                                       with SubscribingRaceActor with ContinuousTimeRaceActor with Positionable {
  case object UpdateFlightPos // publish X-Plane positions on bus
  case object UpdateXPlane    // send proximity positions to X-Plane

  def info (msg: String) = println(msg) // for debugging

  //--- simulated AC
  val id = config.getString("aircraft.id")
  val cs = config.getString("aircraft.cs")

  //--- the ip/port for the socket through which we receive data
  // NOTE - those need to be configured in X-Plane's settings
  val ownIpAddress = InetAddress.getByName(config.getStringOrElse("own-ip-address", "localhost"))
  val ownPort = config.getIntOrElse("own-port", 49003)

  //--- (remote) address and port of the X-Plane apply we send data to
  val remoteIpAddress = InetAddress.getByName(config.getStringOrElse("remote-ip-address", "localhost"))
  val remotePort = config.getIntOrElse("remote-port", 49000)

  // how often do we publish aircraft positions we receive from X-Plane (0 means at receive rate)
  val publishInterval = config.getFiniteDurationOrElse("interval", 0 milliseconds)
  var publishScheduler: Option[Cancellable] = None

  val codec = new XPlaneCodec
  var socket = new DatagramSocket(ownPort, ownIpAddress)
  var publishedFrame = -1L  // last published frame number

  var flightPos = initialFlightPos
  var rpos: RPOS = _ // returned by codec
  def position = flightPos.position

  //--- proximity management
  // how often do we send updated proximity positions to X-Plane (0 means never)
  val proximityInterval = config.getFiniteDurationOrElse("proximity-interval", 200 milliseconds)
  val proximityRange = NauticalMiles(config.getDoubleOrElse("proximity-range", 5.0))
  var proximityScheduler: Option[Cancellable] = None

  val xpAircraft = new XPlaneAircraftList(config.getConfigSeq("other-aircraft"), onOtherShow, onOtherPositionChanged, onOtherHide)
  val proximityList = new FlightsNearList (position, proximityRange, xpAircraft)

  val importThread = ThreadUtils.daemon(readXPlanePositions)



  //--- end initialization

  def readXPlanePositions: Unit = {
    val maxConsecutiveFailures = config.getIntOrElse("max-failures", 5) // if we exceed, we stop reading
    var failures = 0
    val readPacket = codec.readPacket

    while (isLive && (failures < maxConsecutiveFailures)) {
      try {
        while (status == Status.Running) {
          socket.receive(readPacket)
          codec.getHeader(readPacket) match {
            case XPlaneCodec.RPOS_HDR =>
              rpos = codec.readRPOSpacket(readPacket)
              failures = 0
              if (publishScheduler.isEmpty) publishFPos(rpos) // otherwise the actor will take care of publishing
            case hdr => info(s"received unknown UDP message from X-Plane: $hdr")
          }
        }
      } catch {
        case e: Throwable =>
          error(s"error reading X-Plane socket: $e")
          failures += 1
          if (failures == maxConsecutiveFailures) error("max failure threshold reached, terminating X-Plane read thread")
      }
    }
    ifSome(publishScheduler) {_.cancel}
  }

  def initialFlightPos = {
    // <2do> get these from config
    val pos = LatLonPos.fromDegrees(0,0)
    val altitude = Feet(0)
    val speed = Knots(0)
    val heading = Degrees(0)
    FlightPos(id, cs, pos, altitude, speed, heading, simTime)
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {

    super.onInitializeRaceActor(rc, actorConf)
    //sendAirport
    sendOtherAircraft

    //sendOwnAircraft
    sendRPOSrequest
    importThread.start
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)

    if (publishInterval.length > 0) {
      info(s"starting XPlane publish scheduler $publishInterval")
      publishScheduler = Some(scheduler.schedule(0 seconds, publishInterval, self, UpdateFlightPos))
      importThread.start
    }

    if (proximityInterval.length > 0){
      info(s"starting XPlane proximity scheduler $proximityInterval")
      proximityScheduler = Some(scheduler.schedule(0 seconds, proximityInterval, self, UpdateXPlane))
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    ifSome(publishScheduler){ _.cancel }
    ifSome(proximityScheduler){ _.cancel }
    socket.close()
  }

  override def handleMessage = {
    case UpdateFlightPos => publishFPos(rpos)
    case UpdateXPlane => sendProximities
    case BusEvent(_,fpos:FlightPos,_) => updateProximities(fpos)
    case BusEvent(_,fdrop: FlightDropped,_) => dropProximity(fdrop)
  }

  def publishFPos (rpos: RPOS) = {
    if (codec.readFrame > publishedFrame && rpos != null) {  // we might not have gotten the rpos yet
      updatedSimTime

      val pos = LatLonPos.fromDegrees(rpos.latDeg, rpos.lonDeg)
      val altitude = Meters(rpos.elevationMslm)
      val speed = MetersPerSecond(rpos.speedMsec)
      val heading = Degrees(rpos.headingDeg)
      flightPos = FlightPos(id, cs, pos, altitude, speed, heading, simTime)

      publish(flightPos)
      publishedFrame = codec.readFrame

      proximityList.center = pos
    }
  }

  //--- other aircraft updates

  def sendProximities = {
    xpAircraft.updateEstimates(currentSimTimeMillis)
    sendAllXPlaneAircraft
  }

  def updateProximities (fpos: FlightPos) = {
    proximityList.updateWith(fpos)
  }

  def dropProximity (fdrop: FlightDropped) = {
    val cs = fdrop.cs
    if (proximityList.removeFirst { e => e.obj.cs == cs }) println(s"@@ ! $cs : $proximityList")
  }

  //--- the XPlaneAircraftList callbacks, which we use to update X-Plane

  def onOtherShow (e: ACEntry): Unit = {
    //info(s"proximities + ${e.idx} : $proximityList")
    //updateXPlaneAircraft(e)
  }

  def onOtherPositionChanged (e: ACEntry): Unit = {
    println(e.fpos)
    //info(s"proximities * ${e.idx} : $proximityList")
    //updateXPlaneAircraft(e)
  }

  def onOtherHide (e: ACEntry): Unit = {
    info(s"proximities - ${e.idx} : $proximityList")
    e.hide
    //sendHideAircraft(e.idx)
  }

  //--- X-Plane message IO

  def sendPacket (packet: DatagramPacket) = {
    packet.setAddress(remoteIpAddress)
    packet.setPort(remotePort)
    socket.send(packet)
  }

  def sendOwnAircraft = config.getOptionalString("aircraft.type").foreach { s =>
    info(s"setting X-Plane aircraft 0 to $s")
    sendPacket(codec.getACFNpacket(0,s,0))
  }

  // this is only called once during initialization, no need to save iterators
  def sendOtherAircraft () = {
    for (e <- xpAircraft.entries) {
      sendLoadAircraft(e.idx,e.acType,0)
    }
    sendAllXPlaneAircraft
  }

  def sendAirport = config.getOptionalString("airport").foreach { s =>
    info(s"setting X-Plane airport to $s")
    sendPacket(codec.getPAPTpacket(s))
  }

  def sendRPOSrequest = {
    val freqHz = config.getIntOrElse("rpos-frequency", 2)
    info(s"sending RPOS request with frequency $freqHz Hz")
    sendPacket(codec.getRPOSrequestPacket(freqHz))
  }

  def sendLoadAircraft(idx: Int, acType: String, liveryIdx: Int) = {
    info(s"sending ACFN[$idx] = $acType : $liveryIdx")
    sendPacket(codec.getACFNpacket(idx,acType,liveryIdx))
  }

  // this can be called frequently - minimize allocation
  def sendAllXPlaneAircraftVEHA = {
    val n = xpAircraft.length
    info(s"sending VEHA($n)");

    @tailrec def _addVEHAentries ( buf: Array[Byte], idx: Int): Unit = {
      if (idx < n){
        val e = xpAircraft.entries(idx)
        if (e.isVisible) {
          info(f"  $idx: ${e.fpos.cs} = ${e.latDeg}%.3f,${e.lonDeg}%.3f")
          codec.writeVEHAn(buf, e.idx,
            e.latDeg, e.lonDeg, e.altMeters,
            e.psiDeg, e.thetaDeg, e.phiDeg,
            e.gear, e.flaps, e.throttle)
        }
        _addVEHAentries(buf,idx+1)
      }
    }

    val buf = codec.getVEHAbuffer(n)
    _addVEHAentries(buf,0)
    //sendPacket(codec.getVEHApacket(buf))
  }

  def sendAllXPlaneAircraft = {
    val n = xpAircraft.length

    @tailrec def _sendVEH1 (idx: Int): Unit = {
      if (idx < n){
        val e = xpAircraft.entries(idx)
        if (e.isRelevant) {
          info(f"sending VEH1[$idx] =  ${e.latDeg}%.4f,${e.lonDeg}%.4f at ${e.altMeters.toInt}m")
          sendPacket(codec.getVEH1packet( e.idx,
            e.latDeg, e.lonDeg, e.altMeters,
            e.psiDeg, e.thetaDeg, e.phiDeg,
            e.gear, e.flaps, e.throttle))
        }
        _sendVEH1(idx+1)
      }
    }
    _sendVEH1(0)
  }

  def updateXPlaneAircraft (e: ACEntry) = {
    e.updateEstimates( currentSimTimeMillis)
    info(s"sending VEH1[${e.idx}] = ${e.fpos}")
    sendPacket(codec.getVEH1packet(e.idx,
      e.latDeg, e.lonDeg, e.altMeters,
      e.psiDeg, e.thetaDeg, e.phiDeg,
      e.gear, e.flaps, e.throttle))
  }

  def sendFpos (idx: Int, fpos: FlightPos) = { // <2do> still needs pitch/roll info
    info(s"sending VEH1[$idx] = $fpos")
    val pos = fpos.position
    sendPacket(codec.getVEH1packet(idx, pos.φ.toDegrees, pos.λ.toDegrees, fpos.altitude.toMeters,
                                        fpos.heading.toDegrees.toFloat,0f,0f, 1.0f,1.0f,10.0f))
  }

  def sendHideAircraft(idx: Int) = {
    info(s"sending VEH1[$idx] to hide plane")
    sendPacket(codec.getVEH1packet(idx, 0.1,0.1,1000000.0, 0.1f,0.1f,0.1f, 0f,0f,0f))
  }
}
