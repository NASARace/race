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

import java.io.IOException
import java.net._

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.xplane.XPlaneCodec.RPOS
import gov.nasa.race.air.{ExtendedFlightPos, TrackedAircraft}
import gov.nasa.race.common.Status
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor, _}
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.track.{TrackDropped, TrackTerminationMessage, TrackedObject}
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
                                       with SubscribingRaceActor with ContinuousTimeRaceActor with GeoPositioned {
  val MaxPacketLength: Int = 1024

  val netIfc = NetworkInterface.getByName( config.getString("multicast-interface"))
  val beaconGroup = config.getStringOrElse("beacon-group", "239.255.1.1")
  val beaconPort = config.getIntOrElse("beacon-port", 49707)

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

  val ignoreBeacon = config.getBooleanOrElse("ignore-beacon", false)  // do we bypass beacon lookup
  val maxRequest = config.getIntOrElse("max-request", 6)
  val xplaneHost: Option[String] = config.getOptionalString("xplane-host")

  // those are set once we found a matching X-Plane
  var xplaneAddr: InetAddress = null
  var xplanePort: Int = -1

  // how often do we publish aircraft positions we receive from X-Plane (0 means at receive rate)
  val publishInterval = config.getFiniteDurationOrElse("interval", 0 milliseconds)
  var publishScheduler: Option[Cancellable] = None

  val rposFrequency = config.getIntOrElse("rpos-frequency", 2) // in Hz - no decimals

  // if set to 0 we don't check if the connection is still alive and hence we don't try to reconnect
  val checkInterval = config.getFiniteDurationOrElse("check-interval", 10 seconds)

  var codec: XPlaneCodec = new XPlaneCodec // might get reset once we know which X-Plane version we are talking to
  val socket = createSocket

  var receivedRPOS: Long = 0  // last received RPOS
  var publishedRPOS: Long = 0  // last published RPOS

  var flightPos = initialFlightPos

  var rpos: RPOS = _ // returned by codec
  var lastRPOStime: Long = 0

  val proximityPacket = new DatagramPacket(new Array[Byte](MaxPacketLength),MaxPacketLength)

  def position = flightPos.position

  //--- proximity management
  // interval in which we sent periodic updates of external planes to X-Plane (0 means send changed plane on update)
  val proximityInterval = config.getFiniteDurationOrElse("proximity-interval", Duration.Zero)
  val proximityRange = NauticalMiles(config.getDoubleOrElse("proximity-range", 8.0))

  val externalAircraft = createExternalAircraftList
  val proximityList = new FlightsNearList( position, proximityRange, externalAircraft)

  var receiverThread: Thread = ThreadUtils.daemon(runReceiver) // for beacon and RPOS message processing
  var proximityThread: Option[Thread] = None // set on startRaceActor if proximity-interval is non-Zero


  //--- initialization helpers

  def createSocket: DatagramSocket = {
    val h = config.getOptionalString("own-ip-address")
    val p = config.getOptionalInt("own-port")

    if (p.isDefined) {
      if (h.isDefined) new DatagramSocket(p.get, InetAddress.getByName(h.get)) else new DatagramSocket(p.get)
    } else {
      new DatagramSocket
    }
  }

  def createExternalAircraftList: ExternalAircraftList = {
    val ea = createExternalAircraft
    val matcher = getConfigurableOrElse[ExternalAircraftMatcher]("other-matcher")(new FirstUnassignedMatcher)

    if (proximityInterval.length == 0) { // send them as we get them - no estimation required
      new ExternalAircraftList(ea, matcher, onOtherShow, onOtherPositionChanged, onOtherHide)
    } else {
      new ExternalAircraftList(ea, matcher, onOtherShow, noAction, onOtherHide)
    }
  }

  def createExternalAircraft: Array[ExternalAircraft] = {
    var i=0 // we start at index 1 (0 is piloted aircraft)
    config.getConfigArray("other-aircraft").map { acConf =>
      val acType = acConf.getString("type")
      val liveryName = acConf.getStringOrElse("livery-name", ExternalAircraft.AnyLivery)
      val liveryIdx = acConf.getIntOrElse("livery-index", 0)
      i += 1
      if (proximityInterval.length > 0) {
        new ExtrapolatedAC(i, acType,liveryName,liveryIdx)
      } else {
        new NonExtrapolatedAC(i, acType, liveryName, liveryIdx)
      }
    }
  }


  //--- receiver thread

  def runReceiver: Unit = {
    while (status == Status.Running) {
      xplaneState match {
        case Searching => findXPlane
        case Connecting => establishConnection
        case Connected => runConnection
        case Aborted => return
      }
    }
  }

  def findXPlane: Unit = {
    if (ignoreBeacon){
      findConfiguredXplane
    } else {
      findXplaneBeacon
    }
  }

  /*
   * find X-Plane host with configuration data
   */
  def findConfiguredXplane: Unit = {
    xplaneHost match {
      case Some(host) =>
        NetUtils.localInetAddress(host) match {
          case Some(addr) =>
            // we got the host but we don't know yet if there is a X-Plane running on it
            xplaneAddr = addr
            xplanePort = config.getIntOrElse("xplane-port", 49000)
            xplaneState = Connecting

          case None =>
            error(s"unknown xplane-host $host, aborted")
            xplaneState = Aborted
        }

      case None =>
        error("missing xplane-host configuration, aborted")
        xplaneState = Aborted
    }
  }

  def waitForResponse (socket: DatagramSocket, packet: DatagramPacket)(f: =>Unit): Boolean = {
    socket.setSoTimeout(10000)

    var i = 0
    while(i < maxRequest) {
      try {
        f
        info(s"waiting for data ($i/$maxRequest)..")
        socket.receive(packet)
        return true
      } catch {
        case _:SocketTimeoutException => i += 1
      }
    }
    false
  }

  /*
   * find running X-Plane by listening for BECN multicast messages
   */
  def findXplaneBeacon: Unit = {

    val packet = new DatagramPacket(new Array[Byte](MaxPacketLength),MaxPacketLength)
    val sockAddr = new InetSocketAddress( InetAddress.getByName(beaconGroup), beaconPort)  // fixed multicast group for X-Plane BECN messages
    val socket = new MulticastSocket(beaconPort) // fixed multicast port for X-Plane BECN messages

    try {
      socket.joinGroup(sockAddr, netIfc)
    } catch {
      case x:Exception => // multicast disabled in network config, try fallback
        warning(s"multicast on $beaconGroup:$beaconPort not enabled (check firewall), fall back to configured xplane-host")
        findConfiguredXplane
        return
    }

    info("searching for beacon messages")

    while (xplaneState eq Searching){
      if (!waitForResponse(socket,packet){}) {
        warning(s"no data on multicast $beaconGroup:$beaconPort (check network config), fall back to configured xplane-host")
        findConfiguredXplane
        return
      }

      if (codec.isBECNmsg(packet)){
        val becn = codec.readBECNpacket(packet)

        if (becn.hostType == 1 && becn.role == 1) { // check if this is from a master X-Plane
          info(s"got BECN from X-Plane at ${becn.hostName}:${becn.port}")

          ifSome (NetUtils.localInetAddress(becn.hostName)) { becnAddr => // can we resolve the broadcasting beacon host
            info(s"BECN host resolves to $becnAddr")

            if (acceptHost(becnAddr)) {
              info(s"X-Plane host accepted, terminating search")
              if (becn.version < 110000) codec = new XPlane10Codec

              xplaneAddr = becnAddr
              xplanePort = becn.port
              xplaneState = Connecting
            }
          }
        }
      }
    }
  }

  def acceptHost (becnAddr: InetAddress): Boolean = {
    xplaneHost match {
      case Some(host) => NetUtils.isSameHost(becnAddr,host)
      case None => true // no restriction
    }
  }

  def establishConnection: Unit = {
    info("connecting..")
    val packet = new DatagramPacket(new Array[Byte](MaxPacketLength),MaxPacketLength)

    if (!waitForResponse(socket,packet){sendRPOSrequest(packet)}) {
      error("no X-Plane RPOS response, aborting")
      xplaneState = Aborted
      return
    }

    socket.setSoTimeout(0)

    try {
      if (externalAircraft.notEmpty) {
        loadAllExternalAircraft(packet) // this might take a while on X-Plane's side
        socket.receive(packet) // this blocks until X-Plane is responsive again

        if (proximityInterval.length > 0) {
          info(s"starting XPlane proximity export thread with interval $proximityInterval")
          proximityThread = Some(ThreadUtils.startDaemon(runSendProximities))
        }
      }

      if (publishInterval.length > 0) {
        info(s"starting XPlane publish scheduler $publishInterval")
        publishScheduler = Some(scheduler.scheduleWithFixedDelay(0 seconds, publishInterval, self, UpdateFlightPos))
      }

      xplaneState = Connected

    } catch {
      case _: IOException => xplaneState = Searching
    }
  }

  def runConnection: Unit = {
    info("connected")
    val packet = new DatagramPacket(new Array[Byte](MaxPacketLength),MaxPacketLength)

    lastRPOStime = 0
    receivedRPOS = 0
    publishedRPOS = 0

    if (checkInterval.length > 0) {
      info(s"setting receive timeout to ${checkInterval.toMillis} msec")
      socket.setSoTimeout(checkInterval.toMillis.toInt)
    }

    try {
      while (status eq Status.Running) {
        socket.receive(packet)

        if (codec.isRPOSmsg(packet)) {
          rpos = codec.readRPOSpacket(packet)
          lastRPOStime = currentSimTimeMillis
          receivedRPOS += 1

          if (publishScheduler.isEmpty) publishRPOS(rpos) // otherwise the actor will take care of publishing
        }
      }
    } catch {
      case stx: SocketTimeoutException =>
        warning(s"no data from X-Plane within $checkInterval, resetting connection")
        proximityList.clear
        externalAircraft.releaseAssigned
        ifSome(proximityThread) { _.interrupt } // it might be waiting for an assigned aircraft
        xplaneState = Searching

      case iox: IOException =>
        xplaneState = Aborted // actor was terminated

      case t: Throwable =>
        //t.printStackTrace
        error(s"error while connected: $t, aborting connection")
        xplaneState = Aborted
    }

    publishTerminated(rpos)
    ifSome(publishScheduler) {_.cancel()}
  }

  def isConnected: Boolean = xplaneState eq Connected

  /*
   * thread function for sending (possibly estimated) external planes to X-Plane at configured `proximityInterval`
   */
  def runSendProximities: Unit = {
    val packet = new DatagramPacket(new Array[Byte](MaxPacketLength),MaxPacketLength)

    info("proximity export thread running")

    // give it a higher priority to have a steady update rate (we sleep most of the time anyways)
    Thread.currentThread.setPriority(Thread.NORM_PRIORITY + 1)

    while (isConnected) {
      if (externalAircraft.waitForAssigned) { // this blocks until we have an assigned entry

        val t = currentSimTimeMillis
        externalAircraft.foreachAssigned { e =>
          e.updateEstimate(t)
          debug(f"sending estimated VEHx[${e.idx}] =  ${e.cs}: ${e.latDeg}%.4f,${e.lonDeg}%.4f, ${e.psiDeg}%3.0f°, ${e.altMeters.toInt}m")

          if (codec.writeVEHx(packet, e.idx, e.latDeg, e.lonDeg, e.altMeters, e.psiDeg, e.thetaDeg, e.phiDeg,
                                      e.gear, e.flaps, e.throttle) > 0) sendPacket(packet)
        }

        ThreadUtils.sleepInterruptible(proximityInterval)
      }
    }
  }

  def initialFlightPos = {
    // <2do> get these from config
    val pos = GeoPosition.fromDegreesAndFeet(0,0,0)
    val speed = Knots(0)
    val heading = Degrees(0)
    val vr = Speed.Speed0
    val acType = "?"

    new ExtendedFlightPos(id, cs, pos, speed, heading, vr, simTime, 0, Degrees(0), Degrees(0), acType)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    receiverThread.start
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    xplaneState = Aborted
    ifSome(publishScheduler){ _.cancel() }
    ifSome(proximityThread){ _.interrupt } // it might wait for proximities

    socket.close() // this will unblock threads that are in socket.receive

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case UpdateFlightPos => publishRPOS(rpos)
    case BusEvent(_,fpos:TrackedAircraft,_) => updateProximities(fpos)
    case BusEvent(_,term: TrackTerminationMessage,_) => dropProximity(term)
  }

  def computeVr (rpos: RPOS): Speed = MetersPerSecond(rpos.vz)

  def publishRPOS(rpos: RPOS): Unit = {
    if (receivedRPOS > publishedRPOS) {  // we might not have gotten the rpos yet
      updatedSimTime

      val pos = GeoPosition.fromDegreesAndMeters(rpos.latDeg, rpos.lonDeg, rpos.elevationMslm)
      val speed = MetersPerSecond(rpos.speedMsec)
      val heading = Degrees(rpos.headingDeg)
      val vr = computeVr(rpos)
      val pitch = Degrees(rpos.pitchDeg)
      val roll = Degrees(rpos.rollDeg)
      val acType = "?"

      flightPos = new ExtendedFlightPos(id, cs, pos, speed, heading, vr, simTime, 0, pitch,roll,acType)

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

  def dropProximity (term: TrackTerminationMessage) = {
    val cs = term.cs
    proximityList.removeFirst { e => e.obj.cs == cs }
  }

  //--- the XPlaneAircraftList callbacks, which we use to update X-Plane

  def noAction (e: ExternalAircraft): Unit = {}

  def onOtherShow (e: ExternalAircraft): Unit = {
    if (isConnected) {
      info(s"show external aircraft ${e.cs} (${e.idx})")
      sendShowAircraft(proximityPacket,e)
      sendResetAircraft(proximityPacket,e)
    }
  }

  def onOtherPositionChanged (e: ExternalAircraft): Unit = {
    if (isConnected) sendUpdateAircraft(proximityPacket,e)
  }

  def onOtherHide (e: ExternalAircraft): Unit = {
    //info(s"proximities - ${e.idx} : $proximityList")
    if (isConnected) {
      info(s"hide external aircraft ${e.cs} (${e.idx})")
      e.hide
      sendHideAircraft(proximityPacket,e)
    }
  }

  //--- X-Plane message IO

  def sendPacket (packet: DatagramPacket) = {
    packet.setAddress(xplaneAddr)
    packet.setPort(xplanePort)
    socket.send(packet)
  }

  def loadOwnAircraft(packet: DatagramPacket) = config.getOptionalString("aircraft.type").foreach { s =>
    info(s"setting X-Plane aircraft 0 to $s")
    //if (codec.writeACFN(packet,0,s,0)>0) sendPacket(packet) // TODO not yet
  }

  // this is only called once during initialization, no need to save iterators
  def loadAllExternalAircraft(packet: DatagramPacket) = externalAircraft.foreach { e=>
    sendLoadAircraft(packet,e)
    sendResetAircraft(packet,e)
  }

  def sendAirport(packet: DatagramPacket) = config.getOptionalString("airport").foreach { s =>
    info(s"setting X-Plane airport to $s")
    if (codec.writePAPT(packet,s) > 0) sendPacket(packet)
  }

  def sendRPOSrequest(packet: DatagramPacket) = {
    info(s"sending RPOS request with frequency $rposFrequency Hz")
    if (codec.writeRPOSrequest(packet,rposFrequency) > 0) sendPacket(packet)
  }

  def sendLoadAircraft(packet: DatagramPacket, e: ExternalAircraft) = {
    info(s"sending ACPR[${e.idx}] = ${e.acType}")
    if (codec.isInstanceOf[XPlane10Codec]) { // does not support ACPR messages to init+position
      if (codec.writeACFN(packet,e.idx, e.acType, e.liveryIdx) >0) sendPacket(packet)
      sendHideAircraft(packet,e)
    } else {
      // this only works on Linux (Windows crashes)
      //if (codec.writeACPR(packet,e.idx, e.acType, e.liveryIdx,
      //                   e.latDeg, e.lonDeg, e.altMeters + (e.idx + 100), e.psiDeg, e.speedMsec) >0) sendPacket(packet)

      // can't get a struct alignment that works for both Windows and Linux, so we need to send separately
      if (codec.writeACFN(packet,e.idx, e.acType, e.liveryIdx) >0) sendPacket(packet)
      if (codec.writePREL(packet,e.idx,e.latDeg, e.lonDeg, e.altMeters + (e.idx + 100), e.psiDeg, e.speedMsec) >0) sendPacket(packet)
    }

    // turn off autopilot for this external plane
    if (codec.writeDREF(packet,s"sim/multiplayer/autopilot/autopilot_mode[${e.idx}]",0f) >0) sendPacket(packet)

    loopFromTo(0,10) { i =>
      if (codec.writeDREF(packet, s"sim/multiplayer/position/plane${e.idx}_gear_deploy[$i]",0f) >0) sendPacket(packet)
    }
  }

  def sendResetAircraft(packet: DatagramPacket, e: ExternalAircraft): Unit = {
    if (codec.writeDREF(packet,s"sim/multiplayer/controls/gear_request[${e.idx}]",0f) >0) sendPacket(packet)
    if (codec.writeDREF(packet,s"sim/multiplayer/aircraft_is_hit[${e.idx}]",0f) >0) sendPacket(packet)
    if (codec.writeDREF(packet,s"sim/multiplayer/aircraft_is_down[${e.idx}]",0f) >0) sendPacket(packet)
  }

  // VEHA is not supported by XPlane 11 anymore so we only send individual aircraft

  def sendUpdateAircraft(packet: DatagramPacket, e: ExternalAircraft) = {
    debug(s"sending VEHx[${e.idx}] = ${e.fpos}")
    if (codec.writeVEHx(packet, e.idx, e.latDeg, e.lonDeg, e.altMeters,  e.psiDeg, e.thetaDeg, e.phiDeg,
                                e.gear, e.flaps, e.throttle) >0) sendPacket(packet)
    e.hasChanged = false
  }

  // this assumes the ExternalAircraft entry has already been marked as hidden
  def sendHideAircraft(packet: DatagramPacket, e: ExternalAircraft) = {
    val altMeters = e.altMeters + (e.idx + 100)

    if (codec.isInstanceOf[XPlane10Codec]){ // no PREL message
      if (codec.writeVEHx(packet, e.idx,
        e.latDeg, e.lonDeg, altMeters, e.psiDeg, e.thetaDeg, e.phiDeg, 0f, 0f, 0f) >0) sendPacket(packet)
    } else {
      if (codec.writePREL(packet, e.idx, e.latDeg, e.lonDeg, altMeters, e.psiDeg, e.speedMsec)>0) sendPacket(packet)
    }
  }

  // used for initial positioning (which is very dis-continuous)
  def sendShowAircraft(packet: DatagramPacket, e: ExternalAircraft) = {
    if (codec.isInstanceOf[XPlane10Codec]) { // no PREL message
      if (codec.writeVEHx(packet, e.idx, e.latDeg, e.lonDeg, e.altMeters, e.psiDeg, e.thetaDeg, e.phiDeg,
                                  e.gear, e.flaps, e.throttle) > 0) sendPacket(packet)
    } else {
      if (codec.writePREL(packet, e.idx, e.latDeg, e.lonDeg, e.altMeters, e.psiDeg, e.speedMsec) > 0) sendPacket(packet)
    }
  }
}
