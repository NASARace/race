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

import java.io.IOException
import java.net.{Socket, SocketTimeoutException}

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.actor.{SocketDataAcquisitionThread, SocketImporter}
import gov.nasa.race.air.SBSUpdater
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.ChannelTopicProvider
import gov.nasa.race.core.Messages.ChannelTopicRequest
import gov.nasa.race.core.RaceActorCapabilities._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.ifSome
import gov.nasa.race.track.{TrackDropped, TrackedObject}
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{DateTime, Time}

import scala.annotation.tailrec
import scala.concurrent.duration._

/**
  * common base type for SBS import/replay actors, which are ChannelTopicProviders for the configured station id
  */
trait SBSImporter extends ChannelTopicProvider {

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
  * the data acquisition thread of the SBSImportActor
  *
  * this thread reads the socket data and triggers drio checks. Parsing data and detecting stale flights is
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
class SBSDataAcquisitionThread (socket: Socket, bufLen: Int, dropDuration: FiniteDuration,
                                updateFunc: TrackedObject=>Boolean,
                                dropFunc: (String,String,DateTime,Time)=>Unit) extends SocketDataAcquisitionThread(socket) {

  val dropAfter = Milliseconds(dropDuration.toMillis)
  val checkAfter = dropAfter/2
  var lastDropCheck = UndefinedDateTime

  socket.setSoTimeout(dropAfter.toMillis) // value of 0 means no timeout (indefinite wait)

  @inline final def recordLimit(bs: Array[Byte], len: Int): Int = {
    var i = len-1
    while (i>=0 && bs(i) != 10) i -= 1
    i+1
  }

  override def run: Unit = {
    val buf = new Array[Byte](bufLen)
    val in = socket.getInputStream
    val updater = new SBSUpdater(updateFunc,dropFunc)

    lastDropCheck = DateTime.now

    @inline def dropCheck: Unit = {
      val tNow = DateTime.now
      if (tNow - lastDropCheck >= checkAfter) {
        updater.dropStale(tNow,dropAfter)
        lastDropCheck = tNow
      }
    }

    @tailrec @inline def read (buf: Array[Byte], startIdx: Int, maxLen: Int): Int = {
      try {
        in.read(buf,startIdx,maxLen)
      } catch {
        case to: SocketTimeoutException =>
          dropCheck
          read(buf,startIdx,maxLen)
      }
    }

    try {
      var limit = read(buf,0,buf.length)

      while (!isDone.get && limit >= 0) {
        var recLimit = recordLimit(buf,limit)

        while (recLimit == 0) { // no single record in buffer, try to read more
          val nMax = buf.length - limit
          if (nMax <= 0) throw new RuntimeException(s"no linefeed within $buf.length bytes")
          limit += read(buf,limit,nMax) // append - no need to move contents
          recLimit = recordLimit(buf,limit)
        }

        if (updater.initialize(buf,recLimit)) updater.parse
        dropCheck

        if (recLimit < limit){ // last record is incomplete
          val nRemaining = limit - recLimit
          System.arraycopy(buf,recLimit,buf,0,nRemaining)
          limit = nRemaining + read(buf,nRemaining,buf.length - nRemaining)

        } else { // last read record is complete
          limit = read(buf, 0, buf.length)
        }
      }
    } catch {
      case x:IOException => // ? should we make a reconnection effort here?
    }
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
class SBSImportActor (val config: Config) extends SBSImporter with SocketImporter {

  // this is a wall time actor
  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset

  val dropAfter = config.getFiniteDurationOrElse("drop-after", Duration.Zero) // Zero means don't check for drop

  override def defaultPort: Int = 30003

  override def createDataAcquisitionThread (sock: Socket): Option[SocketDataAcquisitionThread] = {
    Some(new SBSDataAcquisitionThread(sock, initBufferSize, dropAfter, publishTrack, dropTrack))
  }

  def publishTrack (track: TrackedObject): Boolean = {
    publish(track)
    true // go on as long as the parser has more data
  }

  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
    publish(TrackDropped(id,cs,date,Some(stationId)))
    info(s"dropping $id ($cs) at $date after $inactive")
  }
}
