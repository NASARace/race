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

import java.io.{IOException, InputStream}
import java.net.{Socket, SocketException, SocketTimeoutException}
import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.actor.{SocketDataAcquisitionThread, SocketImporter}
import gov.nasa.race.air.SbsUpdater
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.ChannelTopicProvider
import gov.nasa.race.core.ChannelTopicRequest
import gov.nasa.race.core.RaceActorCapabilities._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.ifSome
import gov.nasa.race.track.{TrackDropped, Tracked3dObject}
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.NullInputStream

import java.time.ZoneId
import scala.annotation.tailrec
import scala.concurrent.duration._

/**
  * common base type for SBS import/replay actors, which are ChannelTopicProviders for the configured station id
  */
trait SbsImporter extends ChannelTopicProvider {

  val stationId: String = config.getStringOrElse("station-id", "default") // the station we serve
  val stationLocation: Option[GeoPosition] = config.getOptionalGeoPosition("station-location") // where the station is located

  val stationChannel: Option[String] = config.getOptionalString("write-station-to") // where we publish station availability

  override def isRequestAccepted (request: ChannelTopicRequest): Boolean = {
    val channelTopic = request.channelTopic
    if (writeTo.contains(channelTopic.channel)){ // we don't respond to requests through stationChannel
      channelTopic.topic match {
        case Some(id: String) =>
          if (id == stationId) {
            info(s"accepting request for station $id")
            true
          } else false
        case other => false
      }
    } else false
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)){
      ifSome(stationChannel) { publish(_,AdsbStation(stationId,None,stationLocation,true)) }
      true
    } else false
  }
}





/**
  * a SBS (ADS-B text format) importer/translator actor that avoids data copies by
  * direct on-the-fly translation/publication from byte arrays obtained through socket read, bypassing
  * a BufferedInputStream.
  *
  * While this runs against or dedicated actor principle synchronous socket read/translation is the only way
  * to enable a single, re-used byte array input buffer. The direct translation is possible since SBS (CSV)
  * data is both small and simple to parse, hence translation will not increase socket read latency
  */
class SbsImportActor(val config: Config) extends SbsImporter with SocketImporter {

  /**
    * the data acquisition thread of the SBSImportActor
    *
    * this thread reads the socket data and triggers drop checks. Parsing data and detecting stale flights is
    * delegated to an SBSUpdater
    *
    * note that we only perform dropChecks if there is a non-zero dropDuration, and only sync
    * either from a socket read timeout or after a successful read if the checkInterval is exceeded.
    * This saves us not only map synchronization but also additional context switches
    *
    * since drops are not latency critical we do not use a timer to trigger the drop checks but rather perform
    * them sync after detecting that the dropDuration since the last check was exceeded. This also saves us
    * from the need to synchronize messages in the underlying updater. The case that we don't get any data anymore
    * and hence would miss a check is handled by setting a timeout on the socket read (our blocking point).
    * Note that with update cycles around 1s it is most unlikely we ever run into such a socket timeout.
    */
  class SbsDataAcquisitionThread(name: String,
                                 bufLen: Int,
                                 defaultZone: ZoneId,
                                 dropDuration: FiniteDuration,
                                 updateFunc: Tracked3dObject=>Unit,
                                 dropFunc: (String,String,DateTime,Time)=>Unit) extends SocketDataAcquisitionThread(name) {

    val dropAfter = Milliseconds(dropDuration.toMillis.toInt)
    val checkAfter = dropAfter/2
    var lastDropCheck = UndefinedDateTime

    val updater = new SbsUpdater()  // TODO - should we support date adjustment here?
    var in: InputStream = NullInputStream // set during start

    // consecutive failure counts
    var reconnects: Int = 0
    var updateFailures: Int = 0

    initSocket()

    def initSocket(): Unit = {
      ifSome(socket) { sock=>
        sock.setSoTimeout(dropAfter.toMillis.toInt) // value of 0 means no timeout (indefinite wait)
        in = sock.getInputStream
      }
    }

    def dropCheck (): Unit = {
      val tNow = DateTime.now
      if (tNow.timeSince(lastDropCheck) >= checkAfter) {
        updater.dropStale( tNow, dropAfter, dropFunc)
        lastDropCheck = tNow
      }
    }

    // this either returns a positive number of bytes read or throws an exception
    def read (buf: Array[Byte], startIdx: Int, maxLen: Int): Int = {
      while (true) {
        try {
          val n = in.read(buf, startIdx, maxLen) // <<< this blocks until soTimeout is exceeded
          if (n > 0) {
            return n
          } else {
            if (n < 0) {
              // not sure how this happens but it does (on sketchy networks). No use trying to keep the buffer since
              // the socket streams are going to be reset so we just escalate
              throw new IOException("socket input stream closed")
            }
          }

        } catch {
          case _: SocketTimeoutException =>  // no new data yet but we still have to check drops
            if (checkAfter.nonZero) dropCheck()
        }
      }
      0
    }

    @inline final def recordLimit(bs: Array[Byte], len: Int): Int = {
      var i = len-1
      while (i>=0 && bs(i) != 10) i -= 1
      i+1
    }

    def runUpdater (buf: Array[Byte], len: Int): Boolean = {
      try {
        if (updater.initialize(buf, len)) {
          updater.parse( updateFunc)
        }
        dropCheck()
        updateFailures = 0 // updater worked, reset failure count
        true // success

      } catch {
        case x: Throwable =>
          if (updateFailures < maxUpdateFailures) {
            updateFailures += 1
            warning(s"detected socket acquisition thread update failure $updateFailures: $x")
            true // updater failed but we still try again
          } else {
            warning(s"max consecutive update failure count exceeded, terminating socket acquisition thread")
            false // updater failed permanently
          }
      }
    }

    def processSocketInput (f: =>Boolean): Boolean = {
      try {
        f
      } catch {
        case _: IOException =>
          if (!isDone.get || !isTerminating) {
            if (reconnects < maxReconnects) {
              reconnects += 1
              warning("reconnecting socket acquisition thread..")

              if (reconnect()) {
                initSocket()
                info("socket acquisition thread reconnected")
                true // go on, it seems the network has recovered

              } else {
                warning(s"socket acquisition thread reconnect $reconnects failed, trying again..")
                Thread.sleep(300) // give the network some time to recover
                true // network failure, but try once more
              }
            } else {
              warning(s"max reconnect attempts exceeded, terminating socket acquisition thread")
              false // permanent network failure, bail out
            }
          } else false // we are done
      }
    }

    override def run: Unit = {
      info("socket acquisition thread started")

      try {
        val buf = new Array[Byte](bufLen)
        var limit = 0
        lastDropCheck = DateTime.now

        if (!processSocketInput{
          limit = read(buf, 0, buf.length)
          true
        }) return

        while (!isDone.get) {
          var recLimit = recordLimit(buf, limit)

          if (!processSocketInput {
            while (recLimit == 0) { // no single record in buffer, try to read more
              val nMax = buf.length - limit
              if (nMax <= 0) throw new RuntimeException(s"no linefeed within $buf.length bytes")
              limit += read(buf, limit, nMax) // append - no need to move contents
              recLimit = recordLimit(buf, limit)
            }
            reconnects = 0 // successful socket read, reset error count

            // we have input - run the updater
            if (!runUpdater(buf, recLimit)) {
              false // stop - no point reading input if we can't process it

            } else {
              if (recLimit < limit) { // last record was incomplete, append to buffer until full
                val nRemaining = limit - recLimit
                System.arraycopy(buf, recLimit, buf, 0, nRemaining)
                limit = nRemaining + read(buf, nRemaining, buf.length - nRemaining)

              } else { // last read record was complete, refill buffer from start
                limit = read(buf, 0, buf.length)
              }
              true // go on
            }
          }) return
        }
      } finally {
        info("socket acquisition thread terminated")
      }
    }
  }


  // this is a wall time actor
  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset

  val dropAfter = config.getFiniteDurationOrElse("drop-after", 10.seconds) // Zero means don't check for drop

  // those are both consecutive failure counts handled by the acquisition thread
  val maxReconnects: Int = config.getIntOrElse("max-reconnects", 5)
  val maxUpdateFailures: Int = config.getIntOrElse("max-update-failures", 5)

  override def defaultPort: Int = 30003

  override def createDataAcquisitionThread (sock: Socket): Option[SocketDataAcquisitionThread] = {
    // if we import from a dump1090 process we need to be able to explicitly set the timezone
    val defaultZone = config.getMappedStringOrElse("default-zone", ZoneId.of, ZoneId.systemDefault)
    Some(new SbsDataAcquisitionThread(s"$name-input", initBufferSize, defaultZone, dropAfter, publishTrack, dropTrack))
  }

  def publishTrack (track: Tracked3dObject): Unit = {
    publish(track)
  }

  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
    publish(TrackDropped(id,cs,date,Some(stationId)))
    info(s"dropping $id ($cs) at $date after $inactive")
  }
}
