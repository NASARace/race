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

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader}
import java.net.Socket

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.air.FlightPos
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.common.{CsvPullParser, UTF8CsvPullParser}
import gov.nasa.race.core.{ChannelTopicProvider, RaceContext}
import gov.nasa.race.core.RaceActorCapabilities._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.ChannelTopicRequest
import gov.nasa.race.geo.{GeoPosition, LatLonPos}
import gov.nasa.race.ifSome
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.Length._
import gov.nasa.race.util.ThreadUtils

import scala.collection.mutable

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
  * aux class to store aircraft info we get from type 1,4 MSG records
  * that is required to fill in the missing FlightPos fields when receiving a type 3 MSG
  */
class SBSCache {
  //--- from MSG-1 (no need to store time since it is invariant)
  var cs: String = null

  //--- from MSG-4
  var spd: Speed = UndefinedSpeed
  var vr: Speed = UndefinedSpeed
  var hdg: Angle = UndefinedAngle
  var msg4Date: DateTime = UndefinedDateTime
}

class SBSParser extends UTF8CsvPullParser {
  val _MSG_ = Slice("MSG")

  val acCache = new mutable.LongMap[SBSCache](128) // keys are icao24 ids (hex 24bit)

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
            val icao24String = value.intern
            val icao24 = value.toHexLong
            skip(5)
            val cs = readNextValue.intern

            acCache.getOrElseUpdate(icao24, new SBSCache).cs = cs

          case 3 => // airborne position (date,lat,lon,alt)
            skip(2)
            val icao24 = readNextValue.toHexLong

            val ac = acCache.getOrNull(icao24) // avoid allocation
            if (ac != null){
              if (ac.cs != null && ac.hdg.isDefined && ac.spd.isDefined) {
                skip(1)
                val date = readDate
                skip(3)
                val alt = Feet(readNextValue.toInt)
                skip(2)
                val lat = Degrees(readNextValue.toDouble)
                val lon = Degrees(readNextValue.toDouble)

                val fpos = new FlightPos(
                  icao24.toHexString, ac.cs,
                  LatLonPos(lat,lon,alt),
                  ac.spd, ac.hdg, ac.vr,
                  date
                )

                println(fpos) // this is where we publish
              }
            }

          case 4 => // airborne velocity (date,spd,vr,hdg)
            skip(2)
            val icao24 = readNextValue.toHexLong
            skip(1)
            val date = readDate
            skip(4)
            val spd = Knots(readNextValue.toInt)
            val hdg = Degrees(readNextValue.toInt)
            skip(2)
            val vr = FeetPerMinute(readNextValue.toInt)

            val ac = acCache.getOrElseUpdate(icao24, new SBSCache)
            ac.msg4Date = date
            ac.spd = spd
            ac.hdg = hdg
            ac.vr = vr

          case _ => // ignore other MSG types
        }
      }
      skipToEndOfRecord
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
class SBSImportActor (val config: Config) extends SBSImporter {

  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset

  val host = config.getStringOrElse("host", "localhost")
  val port = config.getIntOrElse("port", 30003)

  var sock: Option[Socket] = None // don't connect yet, server might get launched by actor

  // NOTE - buffer has to be larger than max record length so that we always have at least a single \n in it
  val bufLen = Math.max(config.getIntOrElse("buffer-size", 0),4096)

  val thread = ThreadUtils.daemon {
    ifSome(sock) { s =>
      val buf = new Array[Byte](bufLen)
      val in = s.getInputStream
      val parser = new SBSParser

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
  }

  //-- executed from within data acquisition thread - beware of races

  @inline final def recordLimit(bs: Array[Byte], len: Int): Int = {
    var i = len-1
    while (i>=0 && bs(i) != 10) i -= 1
    i+1
  }

  def processBufferRecords (bs: Array[Byte], limit: Int): Unit = {
    System.out.print(new String(bs,0,limit))
  }

  //--- executed in actor thread

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    sock = Some(new Socket(host, port))
    super.onInitializeRaceActor(rc, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    thread.start
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(sock) { _.close }
    super.onTerminateRaceActor(originator)
  }
}
