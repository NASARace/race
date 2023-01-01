/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import com.typesafe.config.Config
import gov.nasa.race.actor.{ReconnectSocket, SocketDataAcquisitionThread, SocketLineImportActor}
import gov.nasa.race.air.SbsUpdater
import gov.nasa.race.common.{ByteSlice, MutRawByteSlice}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActorCapabilities.{SupportsPauseResume, SupportsSimTimeReset}
import gov.nasa.race.ifSome
import gov.nasa.race.track.{TrackDropped, Tracked3dObject}
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.uom.DateTime.UndefinedDateTime
import gov.nasa.race.uom.Time.Milliseconds
import gov.nasa.race.util.ThreadUtils

import java.net.{ConnectException, Socket}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * a SocketLineImportActor for SBS formatted ADS-B messages
  */
class SbsImportActor (conf: Config) extends SocketLineImportActor(conf) with SbsImporter {

  // drop checks are only approximate - we don't try to catch single drops right away
  val dropAfter = Milliseconds(config.getFiniteDurationOrElse("drop-after", 20.seconds).toMillis) // Zero means don't check for drop
  val checkAfter = dropAfter/2
  var lastDropCheck = UndefinedDateTime

  var updateFailures: Int = 0
  val maxUpdateFailures: Int = config.getIntOrElse("max-update-failures", 5)

  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset
  override def defaultPort = 30003

  val updater = new SbsUpdater() // the parser/accumulator for received messages


  //--- configure socket and data acquisition behavior

  // note - this is here because options depend on what data we transmit through this socket. For sbs we know this is generally 1Hz
  // the timeout should be less than our drop check interval so that there is a chance we don't loose history
  override protected def initializeSocket(sock: Socket): Unit = {
    sock.setKeepAlive( conf.getBooleanOrElse("socket-keepalive", true))
    sock.setSoTimeout( conf.getFiniteDurationOrElse("socket-timeout", 15.seconds).toMillis.toInt)
  }

  override protected def initializeDataAcquisitionThread(dat: SocketDataAcquisitionThread): Unit = {
    dat.setLogging(this)
    dat.setConnectionTimeoutHandler( handleSocketTimeout)
    dat.setConnectionLossHandler( (dat,_) => reconnect(dat))
  }

  // NOTE - this is executed from the data acquisition thread, /not/ the actor thread
  override protected def processLine (line: ByteSlice): Boolean = {
    if (line.nonEmpty) {
      try {
        if (updater.initialize(line)) {
          updater.parse( publishTrack)  // this publishes the (complete) tracks for which we have new positions
        }
        dropCheck() // do this sync - this avoids extra context switches and the need to synchronize the updater
        updateFailures = 0 // updater worked, reset failure count

      } catch {
        case x: Throwable =>
          x.printStackTrace()
          if (updateFailures < maxUpdateFailures) {
            updateFailures += 1
            warning(s"detected socket acquisition thread update failure $updateFailures: $x")
            // updater failed but we still try again
          } else {
            warning(s"max consecutive update failure count exceeded, terminating socket acquisition thread")
            return false // updater failed permanently - close data acquisition
          }
      }
    }

    true // go on
  }

  def publishTrack (track: Tracked3dObject): Unit = {
    publish(track)
  }

  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
    publish(TrackDropped(id,cs,date,Some(stationId)))
    info(s"dropping $id ($cs) at $date after $inactive")
  }

  def dropCheck (): Unit = {
    val tNow = DateTime.now
    if (tNow.timeSince(lastDropCheck) >= checkAfter) {
      updater.dropStale( tNow, dropAfter, dropTrack)
      lastDropCheck = tNow
    }
  }
}
