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

package gov.nasa.race.air.xplane

import java.net.{DatagramPacket, DatagramSocket, InetAddress}

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.xplane.XPlaneCodec.RPOS
import gov.nasa.race.air.{ExtendedFlightPos, FlightPos, TrackedAircraft}
import gov.nasa.race.common.Status
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor, _}
import gov.nasa.race.geo.{GeoPosition, LatLonPos}
import gov.nasa.race.track.TrackDropped
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed
import gov.nasa.race.uom.Speed._
import gov.nasa.race.util.ThreadUtils

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * a bridge for the X-Plane flight simulator
  */
class XPlaneActor (val config: Config) extends PublishingRaceActor
                                       with SubscribingRaceActor with ContinuousTimeRaceActor with GeoPosition {
  case object UpdateFlightPos // publish X-Plane positions on bus
  case object UpdateXPlane    // send proximity positions to X-Plane

  //def info (msg: String) = println(msg) // for debugging

  //--- simulated AC
  val id = config.getStringOrElse("aircraft.id", "42")
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

  val codec = createXPlaneCodec
  var socket = new DatagramSocket(ownPort, ownIpAddress)
  var publishedFrame = -1L  // last published frame number

  var flightPos = initialFlightPos
  var rpos: RPOS = _ // returned by codec
  def position = flightPos.position

  //--- proximity management
  // interval in which we sent periodic updates of external planes to X-Plane (0 means send changed plane on update)
  val proximityInterval = config.getFiniteDurationOrElse("proximity-interval", Duration.Zero)
  val proximityRange = NauticalMiles(config.getDoubleOrElse("proximity-range", 5.0))

  val externalAircraft = new ExternalAircraftList( createExternalAircraft, onOtherShow, onOtherPositionChanged, onOtherHide)
  val proximityList = new FlightsNearList( position, proximityRange, externalAircraft)

  val importThread = ThreadUtils.daemon(readXPlanePositions)
  var exportThread: Option[Thread] = None // set on startRaceActor if proximity-interval is non-Zero


  //--- end initialization

  def createXPlaneCodec: XPlaneCodec = {
    config.getIntOrElse("xplane-version",11) match {
      case 10 => new XPlane10Codec
      case 11 => new XPlaneCodec
      case other => throw new RaceException(s"unsupported X-Plane version: $other")
    }
  }

  def createExternalAircraft: Array[ExternalAircraft] = {
    var i=0 // we start at index 1 (0 is piloted aircraft)
    config.getConfigArray("other-aircraft").map { acConf =>
      val acType = acConf.getString("type")
      val livery = 0 // not yet
      i += 1
      if (proximityInterval.length > 0) new ExtrapolatedAC(i, acType,livery) else new NonExtrapolatedAC(i, acType, livery)
    }
  }

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

  def sendExternalPlanes: Unit = {
    val maxConsecutiveFailures = config.getIntOrElse("max-failures", 5) // if we exceed, we stop reading
    var failures = 0

    while (isLive && (failures < maxConsecutiveFailures)) {
      val t = currentSimTimeMillis
      externalAircraft.foreachAssigned { e=>
        e.updateEstimate(t)
        info(f"sending VEHx[${e.idx}] =  ${e.cs}: ${e.latDeg}%.4f,${e.lonDeg}%.4f, ${e.psiDeg}%3.0f°, ${e.altMeters.toInt}m")
        sendPacket(codec.getVEHxPacket( e.idx,
          e.latDeg, e.lonDeg, e.altMeters,
          e.psiDeg, e.thetaDeg, e.phiDeg,
          e.gear, e.flaps, e.throttle))
      }

      Thread.sleep(proximityInterval.toMillis)
    }
  }

  def initialFlightPos = {
    // <2do> get these from config
    val pos = LatLonPos.fromDegrees(0,0)
    val altitude = Feet(0)
    val speed = Knots(0)
    val heading = Degrees(0)
    val vr = Speed.Speed0
    new FlightPos(id, cs, pos, altitude, speed, heading, vr, simTime)
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    //sendAirport
    loadExternalAircraft
    //sendOwnAircraft
    sendRPOSrequest

    super.onInitializeRaceActor(rc, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    importThread.start

    if (publishInterval.length > 0) {
      info(s"starting XPlane publish scheduler $publishInterval")
      publishScheduler = Some(scheduler.schedule(0 seconds, publishInterval, self, UpdateFlightPos))
    }

    if (proximityInterval.length > 0){
      info(s"starting XPlane proximity export thread with interval $proximityInterval")
      exportThread = Some(ThreadUtils.daemon(sendExternalPlanes))
    }

    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(publishScheduler){ _.cancel }
    socket.close()

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case UpdateFlightPos => publishFPos(rpos)
    case BusEvent(_,fpos:TrackedAircraft,_) => updateProximities(fpos)
    case BusEvent(_,fdrop: TrackDropped,_) => dropProximity(fdrop)
  }

  def computeVr (rpos: RPOS) = {
    MetersPerSecond(rpos.vz)
  }

  def publishFPos (rpos: RPOS) = {
    if (codec.readFrame > publishedFrame && rpos != null) {  // we might not have gotten the rpos yet
      updatedSimTime

      val pos = LatLonPos.fromDegrees(rpos.latDeg, rpos.lonDeg)
      val altitude = Meters(rpos.elevationMslm)
      val speed = MetersPerSecond(rpos.speedMsec)
      val heading = Degrees(rpos.headingDeg)
      val vr = computeVr(rpos)
      val pitch = Degrees(rpos.pitchDeg)
      val roll = Degrees(rpos.rollDeg)

      flightPos = new ExtendedFlightPos(id, cs, pos, altitude, speed, heading, vr, simTime, 0, pitch,roll)

      publish(flightPos)
      publishedFrame = codec.readFrame

      proximityList.center = pos
    }
  }

  //--- other aircraft updates


  def updateProximities (fpos: TrackedAircraft) = {
    proximityList.updateWith(fpos)
  }

  def dropProximity (fdrop: TrackDropped) = {
    val cs = fdrop.cs
    proximityList.removeFirst { e => e.obj.cs == cs }
  }

  //--- the XPlaneAircraftList callbacks, which we use to update X-Plane

  def noAction (e: ExternalAircraft): Unit = {}

  def onOtherShow (e: ExternalAircraft): Unit = {
    //info(s"proximities + ${e.idx} : $proximityList")
    updateExternalAircraft(e)
  }

  def onOtherPositionChanged (e: ExternalAircraft): Unit = {
    //info(s"proximities * ${e.idx} : $proximityList")
    updateExternalAircraft(e)
  }

  def onOtherHide (e: ExternalAircraft): Unit = {
    info(s"proximities - ${e.idx} : $proximityList")
    e.hide
    hideExternalAircraft(e)
  }

  //--- X-Plane message IO

  def sendPacket (packet: DatagramPacket) = {
    packet.setAddress(remoteIpAddress)
    packet.setPort(remotePort)
    socket.send(packet)
  }

  def sendOwnAircraft = config.getOptionalString("aircraft.type").foreach { s =>
    info(s"setting X-Plane aircraft 0 to $s")
    //sendPacket(codec.getACFNpacket(0,s,0)) // TODO not yet
  }

  // this is only called once during initialization, no need to save iterators
  def loadExternalAircraft() = externalAircraft.foreach(sendLoadAircraft)

  def sendAirport = config.getOptionalString("airport").foreach { s =>
    info(s"setting X-Plane airport to $s")
    sendPacket(codec.getPAPTpacket(s))
  }

  def sendRPOSrequest = {
    val freqHz = config.getIntOrElse("rpos-frequency", 2)
    info(s"sending RPOS request with frequency $freqHz Hz")
    sendPacket(codec.getRPOSrequestPacket(freqHz))
  }

  def sendLoadAircraft(e: ExternalAircraft) = {
    info(s"sending ACFN[${e.idx} = ${e.acType} : ${e.liveryIdx}")
    sendPacket(codec.getACFNpacket(e.idx,e.acType,e.liveryIdx,
                                   e.latDeg, e.lonDeg, e.altMeters, e.psiDeg, e.speedMsec))
  }

  // VEHA is not supported by XPlane 11 anymore so we only send individual aircraft

  def updateExternalAircraft(e: ExternalAircraft) = {
    info(s"sending VEHx[${e.idx}] = ${e.fpos}")
    sendPacket(codec.getVEHxPacket(e.idx,
                                   e.latDeg, e.lonDeg, e.altMeters,
                                   e.psiDeg, e.thetaDeg, e.phiDeg,
                                   e.gear, e.flaps, e.throttle))
    e.hasChanged = false
  }

  def hideExternalAircraft(e: ExternalAircraft) = {
    info(s"sending VEHx[${e.idx}] to hide plane")
    sendPacket(codec.getVEHxPacket(e.idx,
                                   e.latDeg, e.lonDeg, e.altMeters,
                                   e.psiDeg, e.thetaDeg, e.phiDeg,
                                   e.gear, e.flaps, e.throttle))
  }
}
