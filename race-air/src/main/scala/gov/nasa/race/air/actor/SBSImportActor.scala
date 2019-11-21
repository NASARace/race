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
import java.net.Socket

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.air.FlightPos
import gov.nasa.race.common.UTF8CsvPullParser
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{ChannelTopicRequest, RaceTick}
import gov.nasa.race.core.RaceActorCapabilities._
import gov.nasa.race.core.{ChannelTopicProvider, ContinuousTimeRaceActor, PeriodicRaceActor, RaceContext}
import gov.nasa.race.geo.{GeoPosition, LatLonPos}
import gov.nasa.race.{ifSome, ifTrue}
import gov.nasa.race.track.{TrackDropped, TrackedObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{Angle, DateTime, Speed, Time}

import scala.collection.mutable
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
  * the work horse of the SBSImportActor, which does the socket reads and parses the data
  */
class SBSDataAcquisitionThread (socket: Socket, bufLen: Int,
                                updateFunc: TrackedObject=>Unit,
                                dropFunc: (String,String,DateTime,Time)=>Unit) extends Thread {

  /**
    * aux class to store aircraft info we get from type 1,4 MSG records
    * that is required to fill in the missing FlightPos fields when receiving a type 3 MSG
    */
  class SBSCache (val icao24: Long) {
    var publishDate: DateTime = UndefinedDateTime // when was the last FPos published

    //--- from MSG-1 (no need to store time since it is invariant)
    var cs: String = null
    var icao24String: String = null // not set before we have a cs

    //--- from MSG-4
    var spd: Speed = UndefinedSpeed
    var vr: Speed = UndefinedSpeed
    var hdg: Angle = UndefinedAngle
    var msg4Date: DateTime = UndefinedDateTime
  }

  class SBSImportParser extends UTF8CsvPullParser {
    val _MSG_ = Slice("MSG")

    def parse: Unit = {
      def readDate: DateTime = {
        val dateRange = readNextValue.getIntRange
        val timeRange = readNextValue.getIntRange
        DateTime.parseYMDT(data,dateRange.offset,dateRange.length+timeRange.length+1)
      }

      while (skipToNextRecord) {
        if (readNextValue == _MSG_) {
          readNextValue.toInt match {
            case 1 => // flight identification (cs)
              skip(2)
              parseNextValue
              val icao24 = value.toHexLong
              val icao24String = value.intern
              skip(5)
              val cs = readNextValue.intern

              acCache.synchronized {
                var ac = acCache.getOrElseUpdate(icao24, new SBSCache(icao24))
                ac.cs = cs
                ac.icao24String = icao24String
              }

            case 3 => // airborne position (date,lat,lon,alt)
              skip(2)
              val icao24 = readNextValue.toHexLong

              acCache.synchronized {
                val ac = acCache.getOrNull(icao24) // avoid allocation
                if (ac != null) {
                  if (ac.cs != null && ac.hdg.isDefined && ac.spd.isDefined) {
                    skip(1)
                    val date = readDate
                    skip(3)
                    val alt = Feet(readNextValue.toInt)
                    skip(2)
                    val lat = Degrees(readNextValue.toDouble)
                    val lon = Degrees(readNextValue.toDouble)

                    val status = if (ac.publishDate.isDefined) 0 else TrackedObject.NewFlag
                    ac.publishDate = date

                    val fpos = new FlightPos(
                      ac.icao24String, ac.cs,
                      LatLonPos(lat, lon, alt),
                      ac.spd, ac.hdg, ac.vr,
                      date, status
                    )

                    updateFunc(fpos)
                  }
                }
              }

            case 4 => // airborne velocity (date,spd,vr,hdg)
              skip(2)
              val icao24 = readNextValue.toHexLong
              skip(1)
              val date = readDate
              skip(4)

              // apparently some aircraft report MSG4 without speed and heading
              val spd = if (parseNextNonEmptyValue) Knots(value.toInt) else UndefinedSpeed
              val hdg = if (parseNextNonEmptyValue) Degrees(value.toInt) else UndefinedAngle
              skip(2)
              val vr = if (parseNextNonEmptyValue) FeetPerMinute(value.toInt) else UndefinedSpeed

              acCache.synchronized {
                val ac = acCache.getOrElseUpdate(icao24, new SBSCache(icao24))
                ac.msg4Date = date
                ac.spd = spd
                ac.hdg = hdg
                ac.vr = vr
              }

            case _ => // ignore other MSG types
          }
        }
        skipToEndOfRecord
      }
    }
  }

  @inline final def recordLimit(bs: Array[Byte], len: Int): Int = {
    var i = len-1
    while (i>=0 && bs(i) != 10) i -= 1
    i+1
  }

  // we chose a LongMap based on the assumption that size is limited (<100 entries) and update is much more
  // frequent than removal (LongMap is an open hashmap that requires reorganization)
  val acCache = new mutable.LongMap[SBSCache](128) // keys are icao24 ids (hex 24bit)

  setDaemon(true)

  override def run: Unit = {
    val buf = new Array[Byte](bufLen)
    val in = socket.getInputStream
    val parser = new SBSImportParser

    try {
      var limit = in.read(buf,0,buf.length)

      while (limit >= 0) {
        var recLimit = recordLimit(buf,limit)

        while (recLimit == 0) { // no single record in buffer, try to read more
          val nMax = buf.length - limit
          if (nMax <= 0) throw new RuntimeException(s"no linefeed within $buf.length bytes")
          limit += in.read(buf,limit,nMax) // append - no need to move contents
          recLimit = recordLimit(buf,limit)
        }

        if (parser.initialize(buf,recLimit)) parser.parse

        if (recLimit < limit){ // last record is incomplete
          val nRemaining = limit - recLimit
          System.arraycopy(buf,recLimit,buf,0,nRemaining)
          limit = nRemaining + in.read(buf,nRemaining,buf.length - nRemaining)

        } else { // last read record is complete
          limit = in.read(buf, 0, buf.length)
        }
      }
    } catch {
      case x:IOException => // ? should we make a reconnection effort here?
    }
  }

  def terminate: Unit = {
    socket.shutdownInput
    socket.close // this should interrupt blocked socket reads
  }

  var nRemoved: Int = 0

  // NOTE - this is called from the outside and has to be thread safe
  // we could do this synchronously in the data acquisition loop but then we would only have removals
  // if data was received
  def checkDropped (date: DateTime, dropAfter: Time): Unit = {
    acCache.synchronized {
      acCache.foreachValue { ac=>
        val dt = date.timeSince(ac.publishDate)
        if ( dt > dropAfter) {
          acCache.remove(ac.icao24)
          dropFunc(ac.icao24String, ac.cs, date, dt)

          nRemoved += 1
          if (nRemoved > acCache.size/10) {
            acCache.repack
            nRemoved = 0
          }
        }
      }
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
class SBSImportActor (val config: Config) extends SBSImporter with ContinuousTimeRaceActor with PeriodicRaceActor  {

  // this is a wall time actor
  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset

  val host = config.getStringOrElse("host", "localhost")
  val port = config.getIntOrElse("port", 30003)
  val dropAfter = config.getFiniteDurationOrElse("drop-after", Duration.Zero) // Zero means don't check for drop

  override def defaultTickInterval = dropAfter * 3/2 // derive RaceTick interval - simTime == wall time

  var thread: Option[SBSDataAcquisitionThread] = None // don't create/start yet, the server might get launched by an actor

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    var socket: Socket = null
    try {
      socket = new Socket(host, port)

      // NOTE - buffer has to be larger than max record length so that we always have at least a single \n in it
      val bufLen = Math.max(config.getIntOrElse("buffer-size", 0),4096)
      thread = Some(new SBSDataAcquisitionThread(socket, bufLen, publish, dropTrack))
      super.onInitializeRaceActor(rc, actorConf)

    } catch {
      case x: Throwable  =>
        if (socket != null) socket.close
        error(s"failed to create data acquisition thread: $x")
        false
    }
  }

  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
    publish(TrackDropped(id,cs,date,Some(stationId)))
    info(s"dropping $id ($cs) at $date after $inactive")
  }

  override def onStartRaceActor(originator: ActorRef) = {
    ifSome(thread) { t => t.start }
    super.onStartRaceActor(originator)
  }

  override def onRaceTick: Unit = {
    ifSome(thread) { t=>
      t.checkDropped(DateTime.ofEpochMillis(currentSimTimeMillis), Milliseconds(dropAfter.toMillis))
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(thread) { t =>
      t.terminate
      thread = None
    }
    super.onTerminateRaceActor(originator)
  }
}
