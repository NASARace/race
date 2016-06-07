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

package gov.nasa.race.actors.bridges

import java.net.{InetAddress, DatagramSocket, DatagramPacket}

import akka.actor.{Cancellable, ActorRef}
import com.typesafe.config.Config
import gov.nasa.race.actors.bridges.XPlaneCodec.RPOS
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core._
import gov.nasa.race.core.{SubscribingRaceActor, PublishingRaceActor}
import gov.nasa.race.data.filters.ProximitySet
import gov.nasa.race.data.{Positionable, LatLonPos, FlightPos}
import squants.motion.{MetersPerSecond, Knots, UsMilesPerHour}
import squants.space.{Meters, NauticalMiles, Degrees, Feet}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * a bridge for the X-Plane flight simulator
  */
class XPlaneActor (val config: Config) extends PublishingRaceActor
                                       with SubscribingRaceActor with ContinuousTimeRaceActor with Positionable {

  case object UpdateFlightPos

  val writeTo = config.getString("write-to")

  //--- simulated AC
  val id = config.getString("id")
  val cs = config.getString("cs")

  //--- the ip/port for the socket through which we receive data
  // NOTE - those need to be configured in X-Plane's settings
  val ownIpAddress = InetAddress.getByName(config.getStringOrElse("own-ip-address", "localhost"))
  val ownPort = config.getIntOrElse("own-port", 49003)

  //--- (remote) address and port of the X-Plane apply we send data to
  val remoteIpAddress = InetAddress.getByName(config.getStringOrElse("remote-ip-address", "localhost"))
  val remotePort = config.getIntOrElse("remote-port", 49000)

  // how often do we publish (changed) sim readings (0 means at receive rate)
  val publishInterval = config.getIntOrElse("interval-sec", 0)

  // max number of consecutive failures after which we stop reading
  val maxConsecutiveFailures = config.getIntOrElse("max-failures", 5)

  var publishScheduler: Option[Cancellable] = None
  val codec = new XPlaneCodec
  var socket = new DatagramSocket(ownPort, ownIpAddress)
  var publishedFrame = -1L  // last published frame number

  val proximityRange = NauticalMiles(config.getDoubleOrElse("proximity-range", 5.0))
  val maxProximities = config.getIntOrElse("max-proximities", 10)
  var rpos: RPOS = _ // returned by codec
  var flightPos = initialFlightPos
  val proximitySet = new ProximitySet[FlightPos](this, proximityRange, maxProximities, (fp1,fp2) => {fp1.cs == fp2.cs})
  val xplaneAC = new XPlaneArray
  def position = flightPos.position

  val importThread = new Thread( new Runnable {
    override def run: Unit = {
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
  })
  importThread.setDaemon(true)

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    sendOwnAircraft
    sendAirport
    sendRPOSrequest
    if (publishInterval > 0) {
      publishScheduler = Some(scheduler.schedule(0 seconds, publishInterval seconds, self, UpdateFlightPos))
    }
    importThread.start
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    ifSome(publishScheduler){ _.cancel }
    socket.close()
  }

  override def handleMessage = {
    case UpdateFlightPos => publishFPos(rpos)
    case BusEvent(_,fpos:FlightPos,_) => updateProximities(fpos)
  }

  def publishFPos (rpos: RPOS) = {
    if (codec.readFrame > publishedFrame){
      updatedSimTime

      val pos = LatLonPos.fromDegrees(rpos.latDeg, rpos.lonDeg)
      val altitude = Meters(rpos.elevationMslm)
      val speed = MetersPerSecond(rpos.speedMsec)
      val heading = Degrees(rpos.headingDeg)
      flightPos = FlightPos(id, cs, pos, altitude, speed, heading, simTime)

      publish(writeTo, flightPos)
      publishedFrame = codec.readFrame
    }
  }

  def updateProximities (fpos: FlightPos) = {
    /** not yet, proximities need to be refactored
    proximitySet.updatePositions
    if (proximitySet.sortIn(fpos)){
      proximitySet.dump // just debug for now
    }
    **/

    val cs = fpos.cs
    var idx = xplaneAC.getIndex(cs)
    if (idx < 0) {
      val acType = getAcType(cs)
      idx = xplaneAC.add(cs, acType)
      if (idx >= 0) sendOtherAircraft(idx, acType)
    }
    if (idx > 0) sendFpos(idx, fpos)
  }

  def initialFlightPos = {
    // <2do> get these from config
    val pos = LatLonPos.fromDegrees(0,0)
    val altitude = Feet(0)
    val speed = UsMilesPerHour(0)
    val heading = Degrees(0)
    FlightPos(id, cs, pos, altitude, speed, heading, simTime)
  }

  def sendPacket (packet: DatagramPacket) = {
    packet.setAddress(remoteIpAddress)
    packet.setPort(remotePort)
    socket.send(packet)
  }

  def sendOwnAircraft = config.getOptionalString("aircraft").foreach { s =>
    info(s"setting X-Plane aircraft 0 to $s")
    sendPacket(codec.getACFNpacket(0,s))
  }

  def sendOtherAircraft (idx: Int, s: String) = {
    info(s"setting X-Plane aircraft $idx to $s")
    //sendPacket(codec.getACFNpacket(idx,s))
  }

  def sendAirport = config.getOptionalString("airport").foreach { s =>
    info(s"setting X-Plane airport to $s")
    sendPacket(codec.getPAPTpacket(s))
  }

  def sendRPOSrequest = {
    val freqHz = config.getIntOrElse("rpos-frequency", 1)
    info(s"sending RPOS request with frequency $freqHz Hz")
    sendPacket(codec.getRPOSrequestPacket(freqHz))
  }

  def sendFpos (idx: Int, fpos: FlightPos) = { // <2do> still needs pitch/roll info
    info(s"sending VEH1[$idx] = $fpos")
    val pos = fpos.position
    sendPacket(codec.getVEH1packet(idx, pos.φ.toDegrees, pos.λ.toDegrees, fpos.altitude.toMeters,
                                        fpos.heading.toDegrees.toFloat,0f,0f, 0f,0f,0f))
  }

  def getAcType (cs: String) = { // <2do> just a mockup for now, the sky is full of NASA B747s
    "Aircraft/Heavy Metal/B747-100 NASA/B747-100 NASA.acf"
  }
}