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

package gov.nasa.race.air.translator

import com.typesafe.config.Config
import gov.nasa.race.air.FlightPos
import gov.nasa.race.track.TrackedObject.ChangedCS
import gov.nasa.race.config._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.DateTime

import java.time.ZoneId
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap

object SBS2FlightPos {
  // SBS date-time group, e.g. ...,2016/03/11,13:07:18.054,...", parse with DateTime.parseYMDT(s)
  // NOTE - sbs reports without time zone (assuming local), hence we need this parser

  // this is sub-optimal - translators should not have state. Unfortunately, the alternative
  // would be to create intermediate objects and hand them back to our translator, and the
  // mode-S-to-callsign mapping is actually a shared piece of data that can be used by multiple
  // clients (assume one actor per ADS-B receiver station, and aircraft passing between them)
  class AircraftInfo (var icao24: String,  // as hex string (we always get it)
                      var cs: String=null, // set for real by MSG1
                      var dtg: String=null, // the last update (which might not be position)
                      var posDtg: String=null, // NOTE we only update when we have a position
                      var lat: String=null,
                      var lon: String=null,
                      var alt: String=null,
                      var speed: String=null, // ground speed (not IAS)
                      var track: String=null,
                      var vr: String= null  // vertical rate (ft/min)
                     ) {
    var oldCS: String = null

    override def toString: String = s"AircraftInfo($dtg,$cs: pos=($lat,$lon,$alt), spd=$speed, alt=$track)"

    @inline def setCS (s: String) = if (s != null) {
      oldCS = if (cs == s) null else cs
      cs = s
    }
    @inline def setDtg (s: String) = if (s != null) dtg = s
    @inline def setPosDtg = posDtg = dtg
    @inline def setLat (s: String) = if (s != null) lat = s
    @inline def setLon (s: String) = if (s != null) lon = s
    @inline def setAlt (s: String) = if (s != null) alt = s
    @inline def setSpeed (s: String) = if (s != null) speed = s
    @inline def setTrack (s: String) = if (s != null) track = s
    @inline def setVr (s: String) = if (s != null) vr = s

    def tryToFlightPos (defaultZone: ZoneId) : Option[FlightPos] = {
      if (cs !=null && posDtg !=null && lat !=null && lon !=null && speed !=null && alt !=null && track !=null) {
        val fpos = new FlightPos(icao24, cs,
          GeoPosition(Degrees(lat.toDouble), Degrees(lon.toDouble), Feet(alt.toDouble)),
          Knots(speed.toDouble),
          Degrees(track.toDouble),
          FeetPerMinute(vr.toDouble),
          DateTime.parseYMDT(posDtg, defaultZone))
        if (oldCS != null) fpos.amend(ChangedCS(oldCS))
        Some(fpos)
      } else None
    }
  }

  val acInfos = TrieMap.empty[String,AircraftInfo]

  def clear = acInfos.clear()
}

/**
  * translator of SBS socket 30003 messages to FlightPos objects
  *
  * NOTE - this translator is stateful, depending on input source. We can only produce FlightPos
  * objects once we know aircraft callsign, position, speed, alt and heading, which might require
  * several SBS messages (MSG1,MSG3 and MSG4 in case of dump1090 source - see example)
  *
  * since it seems quite common to have a significant amount of time between the first MSG3
  * (position) and MSG1 (aircraft identification) records, but most clients use the CS as the
  * identifier (there is no ICAO24 mode S id in NasFlight messages), we support a configurable
  * mode ('temp-cs') in which we generate an artificial "?xxxxxx" cs from ICAO24 (if CS isn't
  * set yet)
  *
  * since this translator might be called frequently, we should avoid any temporary objects
  *
  * SBS as documented on http://woodair.net/SBS/Article/Barebones42_Socket_Data.htm
  * Message examples:
  *  MSG,1,111,11111,AA2BC2,111111,2016/03/11,13:07:16.663,2016/03/11,13:07:16.626,UAL814  ,,,,,,,,,,,0
  *  MSG,3,111,11111,A04424,111111,2016/03/11,13:07:05.343,2016/03/11,13:07:05.288,,11025,,,37.17274,-122.03935,,,,,,0
  *  MSG,4,111,11111,AC1FCC,111111,2016/03/11,13:07:07.777,2016/03/11,13:07:07.713,,,316,106,,,1536,,,,,0
  *
  * fields:
  *   1: message type (MSG, SEL, ID, AIR, STA, CLK)
  *   2: transmission type (MSG only: 1-8, 3: ES Airborne Position Message)
  *   3: DB session id   - '111' for dump1090 generated SBS
  *   4: DB aircraft id  - '11111' for dump1090 generated SBS
  *   5: ICAO 24 bit id (mode S transponder code)
  *   6: DB flight id - '111111' for dump1090 generated SBS
  *   7: date generated
  *   8: time generated
  *   9: date logged
  *  10: time logged
  *  11: callsign
  *  12: mode-C altitude (relative to 1013.2mb (Flight Level), *not* AMSL)
  *  13: ground speed
  *  14: track (from vx,vy, *not* heading)
  *  15: latitude
  *  16: longitude
  *  17: vertical rate (ft/min - 64ft resolution)
  *  18: squawk (mode-A squawk code)
  *  19: alert (flag indicating squawk has changed)
  *  20: emergency (flag)
  *  21: spi (flag, transponder ident activated)
  *  22: on ground (flag)
  *
  *  see also http://mode-s.org/decode/
  */
class SBS2FlightPos (val config: Config=NoConfig) extends ConfigurableTranslator {
  import SBS2FlightPos._

  var useTempCS = if (config != null) config.getBooleanOrElse("temp-cs",false) else false
  val defaultZone = {
    val zid = if (config != null) config.getStringOrElse("default-zone", null) else null
    if (zid == null) ZoneId.systemDefault() else ZoneId.of(zid)
  }

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String => translateChars(s.toCharArray)
      case _ => None
    }
  }

  private def translateChars(buf: Array[Char]): Option[FlightPos] = {
    var i0 = 0
    val len = buf.length

    @tailrec def trimLeadingSpaces (i: Int): Int = if (i < len && buf(i+1) == ' ') trimLeadingSpaces(i+1) else i
    @tailrec def trimTrailingSpaces (i: Int): Int = if (buf(i-1) == ' ') trimTrailingSpaces(i-1) else i
    @tailrec def nextFields (n: Int, i: Int): String = {
      if (i >= len) null
      else if (buf(i) == ',') {
        if (n <= 1) {
          val j0 = i0
          i0 = trimLeadingSpaces(i+1)
          val j1 = trimTrailingSpaces(i)
          if (j1 == j0) null else  new String(buf, j0, j1 - j0)
        } else {
          nextFields(n-1, i+1)
        }
      } else {
        nextFields(n, i+1)
      }
    }
    @tailrec def skipNextFields(n: Int, i: Int): Unit = {
      if (i < len) {
        if (buf(i) == ',') {
          if (n == 1)  i0 = i + 1
          else skipNextFields(n-1, i+1)
        } else {
          skipNextFields(n, i + 1)
        }
      }
    }
    @inline def getAcInfo = {
      skipNextFields(2,i0) // DB sessionId, aircraftId
      val icao24 = nextFields(1,i0)
      val acInfo = acInfos.getOrElseUpdate(icao24,
        if (useTempCS) new AircraftInfo(icao24, TrackedObject.tempCS(icao24))
        else new AircraftInfo(icao24))

      skipNextFields(1,i0) // DB flightId
      acInfo.setDtg(nextFields(2,i0))
      acInfo
    }

    try {
      val msgType = nextFields(1,i0)
      msgType match {
        case "MSG" =>
          val transType = nextFields(1,i0)

          transType.charAt(0) match {
            case '1' => // identification & category, just update
              val acInfo = getAcInfo
              skipNextFields(2,i0) // dateLogged , timeLogged - don't overwrite msg 3 time stamps
              val cs = nextFields(1,i0)
              if (cs != acInfo.cs) { // don't return a flight pos unless we have a changed cs
                acInfo.setCS(cs) // watch out - dump1090 has trailing spaces
                acInfo.tryToFlightPos(defaultZone)
              } else None

            case '3' =>  // airborne position message, report if we already got id and velocity
              val acInfo = getAcInfo
              acInfo.setPosDtg
              skipNextFields(2, i0) // dateLogged , timeLogged
              acInfo.setCS(nextFields(1,i0))
              acInfo.setAlt(nextFields(1,i0))
              acInfo.setSpeed(nextFields(1,i0))
              acInfo.setTrack(nextFields(1,i0))
              acInfo.setLat(nextFields(1,i0))
              acInfo.setLon(nextFields(1,i0))
              // we ignore the rest for now
              acInfo.tryToFlightPos(defaultZone)


            case '4' => // airborne velocity, just update
              val acInfo = getAcInfo
              skipNextFields(4,i0)
              acInfo.setSpeed(nextFields(1,i0))
              acInfo.setTrack(nextFields(1,i0))
              skipNextFields(2,i0)
              acInfo.setVr(nextFields(1,i0))
              None // don't return a FlightPos until we have a position (clients might not check the timestamp)

            case _ => None
          }

        case _ => None
      }
    } catch {
      case _:Throwable => None
    }
  }
}
