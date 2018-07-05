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

import java.net._

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.xplane.XPlaneCodec.RPOS
import gov.nasa.race.air.{ExtendedFlightPos, TrackedAircraft}
import gov.nasa.race.common.Status
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor, _}
import gov.nasa.race.geo.{GeoPosition, LatLonPos}
import gov.nasa.race.track.{TrackDropped, TrackedObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed
import gov.nasa.race.uom.Speed._
import gov.nasa.race.util.{NetUtils, ThreadUtils}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


/**
  * a bridge for the X-Plane flight simulator
  */
class XPlaneActor (val config: Config) extends PublishingRaceActor
                                       with SubscribingRaceActor with ContinuousTimeRaceActor with GeoPosition {
  //--- xplane actor specific state
  sealed trait XPlaneState
  case object Searching extends XPlaneState
  case object Connecting extends XPlaneState
  case object Connected extends XPlaneState
  case object Aborted extends XPlaneState // don't try to reconnect

  //--- self messages
  case object StartReceiver // start receiver thread
  case object StartPeriodicTasks
  case object UpdateFlightPos // publish X-Plane positions on bus

  var xplaneState: XPlaneState = Searching

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

  val rposFrequency = config.getIntOrElse("rpos-frequency", 2) // in Hz - no decimals

  // if set to 0 we don't check if the connection is still alive and hence we don't try to reconnect
  val checkInterval = config.getFiniteDurationOrElse("check-interval", 2*(1000 / rposFrequency) milliseconds)

  val codec = createXPlaneCodec
  var socket = new DatagramSocket(ownPort, ownIpAddress)

  var receivedRPOS: Long = 0  // last received RPOS
  var publishedRPOS: Long = 0  // last published RPOS

  var flightPos = initialFlightPos
  var rpos: RPOS = _ // returned by codec
  var lastRPOStime: Long = 0

  def position = flightPos.position

  //--- proximity management
  // interval in which we sent periodic updates of external planes to X-Plane (0 means send changed plane on update)
  val proximityInterval = config.getFiniteDurationOrElse("proximity-interval", Duration.Zero)
  val proximityRange = NauticalMiles(config.getDoubleOrElse("proximity-range", 8.0))

  val externalAircraft = if (proximityInterval.length == 0) {
    new ExternalAircraftList(createExternalAircraft, onOtherShow, onOtherPositionChanged, onOtherHide)
  } else {
    new ExternalAircraftList(createExternalAircraft, noAction, noAction, onOtherHide)
  }
  val proximityList = new FlightsNearList( position, proximityRange, externalAircraft)

  var receiverThread: Thread = ThreadUtils.daemon(runReceiver) // for beacon and RPOS message processing
  var proximityThread: Option[Thread] = None // set on startRaceActor if proximity-interval is non-Zero


  //--- initialization helpers

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


  //--- receiver thread

  def runReceiver: Unit = {
    while (status == Status.Running) {
      xplaneState match {
        case Searching => readXPlaneBeaconMessages
        case Connecting => establishConnection
        case Connected => runConnection
        case Aborted => return
      }
    }
  }

  /*
 * thread function for receiving BECN messages to detect running X-Plane instances
 * If we get a BECN from our configured X-Plane we automatically start to initialize
 *
 * NOTE - this never runs at the same time as the RPOS receiver or the proximity sender
 */
  def readXPlaneBeaconMessages: Unit = {
    val buf = new Array[Byte](1024)  // packet length depends on hostname, which can be up to 500 char
    val packet = new DatagramPacket(buf,buf.length)

    val becnAddr = InetAddress.getByName("239.255.1.1")  // fixed multicast group for X-Plane BECN messages
    val socket = new MulticastSocket(49707) // fixed multicast port for X-Plane BECN messages
    socket.joinGroup(becnAddr);

    info("searching for beacon messages")

    while (xplaneState eq Searching){
      socket.receive(packet)
      if (codec.isBECNmsg(packet)){
        val becn = codec.readBECNpacket(packet)

        if (becn.hostType == 1 && becn.role == 1) { // check if it's from a master X-Plane
          info(s"got BECN from X-Plane at ${becn.hostName}:${becn.port}")

          ifSome(NetUtils.localInetAddress(becn.hostName)) { addr => // can we resolve the BECN hostname
            if (NetUtils.isSameHost(addr,remoteIpAddress)) {
              info(s"X-Plane host accepted, terminating search")
              xplaneState = Connecting
            }
          }
        }
      }
    }
  }

  def establishConnection: Unit = {
    val readPacket = codec.readPacket
    info("connecting..")

    socket.setSoTimeout(0)
    sendRPOSrequest
    socket.receive(readPacket) // this blocks until we get the first RPOS message - just wait until X-Plane talks to us

    if (externalAircraft.notEmpty) {
      loadExternalAircraft // this might take a while on X-Plane's side
      socket.receive(readPacket) // this blocks until X-Plane is responsive again

      if (proximityInterval.length > 0){
        info(s"starting XPlane proximity export thread with interval $proximityInterval")
        proximityThread = Some(ThreadUtils.startDaemon(sendExternalPlanes))
      }
    }

    if (publishInterval.length > 0) {
      info(s"starting XPlane publish scheduler $publishInterval")
      publishScheduler = Some(scheduler.schedule(0 seconds, publishInterval, self, UpdateFlightPos))
    }

    xplaneState = Connected
  }

  def runConnection: Unit = {
    val readPacket = codec.readPacket
    info("connected")

    lastRPOStime = 0
    receivedRPOS = 0
    publishedRPOS = 0

    if (checkInterval.length > 0) {
      info(s"setting receive timeout to ${checkInterval.toMillis}")
      socket.setSoTimeout(checkInterval.toMillis.toInt)
    }

    try {
      while (status eq Status.Running) {
        socket.receive(readPacket)

        if (codec.isRPOSmsg(readPacket)) {
          rpos = codec.readRPOSpacket(readPacket)
          lastRPOStime = currentSimTimeMillis
          receivedRPOS += 1

          if (publishScheduler.isEmpty) publishRPOS(rpos) // otherwise the actor will take care of publishing
        }
      }
    } catch {
      case stx: SocketTimeoutException =>
        warning(s"no data from X-Plane within $checkInterval, resetting connection")
        proximityList.clear
        externalAircraft.releaseAll
        xplaneState = Searching

      case scx: SocketException =>
        xplaneState = Aborted // actor was terminated

      case t: Throwable =>
        //t.printStackTrace
        error(s"error while connected: $t, aborting connection")
        xplaneState = Aborted
    }

    publishTerminated(rpos)
    ifSome(publishScheduler) {_.cancel}
  }

  def isConnected: Boolean = xplaneState eq Connected

  /*
   * thread function for sending (possibly estimated) external planes to X-Plane at configured `proximityInterval`
   */
  def sendExternalPlanes: Unit = {
    info("proximity export thread running")

    while (isConnected) {
      val t = currentSimTimeMillis
      externalAircraft.foreachAssigned { e=>
        e.updateEstimate(t)
        info(f"sending estimated VEHx[${e.idx}] =  ${e.cs}: ${e.latDeg}%.4f,${e.lonDeg}%.4f, ${e.psiDeg}%3.0f°, ${e.altMeters.toInt}m")
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
    new ExtendedFlightPos(id, cs, pos, altitude, speed, heading, vr, simTime, 0, Degrees(0), Degrees(0))
  }

  override def onStartRaceActor(originator: ActorRef) = {
    receiverThread.start
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    xplaneState = Aborted
    ifSome(publishScheduler){ _.cancel }
    // exportThread is not blocking indefinitely and will terminate

    socket.close()

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case UpdateFlightPos => publishRPOS(rpos)
    case BusEvent(_,fpos:TrackedAircraft,_) => updateProximities(fpos)
    case BusEvent(_,fdrop: TrackDropped,_) => dropProximity(fdrop)
  }

  def computeVr (rpos: RPOS): Speed = MetersPerSecond(rpos.vz)

  def publishRPOS(rpos: RPOS): Unit = {
    if (receivedRPOS > publishedRPOS) {  // we might not have gotten the rpos yet
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
      publishedRPOS = receivedRPOS

      proximityList.center = pos
    }
  }

  def publishTerminated (rpos: RPOS): Unit = {
    if (publishedRPOS > 0) { // if nothing was published yet we don't have to drop anything
      flightPos = flightPos.copyWithStatus(TrackedObject.DroppedFlag)
      publish(flightPos)
    }
  }

  //--- other aircraft updates


  def updateProximities (fpos: TrackedAircraft) = {
    if (isConnected) proximityList.updateWith(fpos)
  }

  def dropProximity (fdrop: TrackDropped) = {
    val cs = fdrop.cs
    proximityList.removeFirst { e => e.obj.cs == cs }
  }

  //--- the XPlaneAircraftList callbacks, which we use to update X-Plane

  def noAction (e: ExternalAircraft): Unit = {}

  def onOtherShow (e: ExternalAircraft): Unit = {
    //info(s"proximities + ${e.idx} : $proximityList")
    if (isLive) updateExternalAircraft(e)
  }

  def onOtherPositionChanged (e: ExternalAircraft): Unit = {
    //info(s"proximities * ${e.idx} : $proximityList")
    if (isLive) updateExternalAircraft(e)
  }

  def onOtherHide (e: ExternalAircraft): Unit = {
    info(s"proximities - ${e.idx} : $proximityList")
    e.hide
    if (isLive) hideExternalAircraft(e)
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
  def loadExternalAircraft() = externalAircraft.foreach { e=>
    sendLoadAircraft(e)
    hideExternalAircraft(e)  // stash them away until they become proximities
  }

  def sendAirport = config.getOptionalString("airport").foreach { s =>
    info(s"setting X-Plane airport to $s")
    sendPacket(codec.getPAPTpacket(s))
  }

  def sendRPOSrequest = {
    info(s"sending RPOS request with frequency $rposFrequency Hz")
    sendPacket(codec.getRPOSrequestPacket(rposFrequency))
  }

  def sendLoadAircraft(e: ExternalAircraft) = {
    info(s"sending ACFN[${e.idx}] = ${e.acType}")
      sendPacket(codec.getACFNpacket(e.idx, e.acType, e.liveryIdx,
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
