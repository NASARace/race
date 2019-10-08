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
package gov.nasa.race.air.translator

import java.lang.Double.isFinite

import com.typesafe.config.Config
import gov.nasa.race.air.AsdexTrack
import gov.nasa.race.air.AsdexTrack._
import gov.nasa.race.common.StringXmlPullParser2
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.track.TrackedObject._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.{ifSome, withSomeOrElse}

import scala.Double.NaN
import scala.collection.mutable.{ArrayBuffer, HashMap => MutHashMap}

/**
  * translator for SWIM AsdexMsg (ASDE-X) XML messages
  */
class AsdexMsgParser (val config: Config=NoConfig)
  extends StringXmlPullParser2(config.getIntOrElse("buffer-size",200000)) with ConfigurableTranslator {

  val asdexMsg = Slice("asdexMsg")
  val ns2$asdexMsg = Slice("ns2:asdexMsg")
  val airport = Slice("airport")
  val positionReport = Slice("positionReport")
  val aircraft = Slice("aircraft")
  val vehicle = Slice("vehicle")
  val up = Slice("up")
  val down = Slice("down")
  val veh = Slice("VEH")
  val unavailable = Slice("unavailable")

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String => parse(s)
      case Some(s: String) => parse(s)
      case _ => None // nothing else supported yet
    }
  }

  protected def parse (msg: String): Option[Any] = {
    if (initialize(msg)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == ns2$asdexMsg || tag == asdexMsg) return parseAsdexMsg
        }
      }
    }
    None
  }

  protected def parseAsdexMsg: Option[Any] = {
    var airportId: String = null
    val tracks = new ArrayBuffer[AsdexTrack](120)

    while (parseNextTag) {
      if (isStartTag) {
        if (tag == airport) {
          if (parseSingleContentString) airportId = contentString.intern
        } else if (tag == positionReport) {
          if (airportId != null) parsePositionReport(airportId,tracks)
        }
      }
    }

    if (tracks.nonEmpty) Some(tracks) else None
  }

  protected def parsePositionReport (airportId: String, tracks: ArrayBuffer[AsdexTrack]): Unit = {
    // note that we have to use different values for optionals that might not be in a delta update so that
    // we can distinguish from cached values
    var trackId: String = null
    var date: DateTime = DateTime.UndefinedDateTime
    var latDeg, lonDeg: Double = NaN
    var altFt: Double = NaN
    var hdgDeg: Double = NaN
    var spdMph: Double = NaN
    var vertRate: Double = NaN
    var status: Int = 0
    var acId: String = null
    var acType: String = null

    while (parseNextTag) {
      val data = this.data
      val off = tag.offset
      val len = tag.length

      if (isStartTag) {

        @inline def readTargetTypeFlag: Int = {
          parseContent
          getNextContentString
          if (contentString == aircraft) AircraftFlag
          else if (contentString == vehicle) VehicleFlag
          else 0
        }

        @inline def readUpDownFlag: Int = {
          parseContent
          getNextContentString
          if (contentString == up) UpFlag
          else if (contentString == down) DownFlag
          else 0
        }

        @inline def readACType: String = {
          if (parseContent) {
            getNextContentString
            if (contentString == veh) null
            else if (contentString.isEmpty) null
            else contentString.intern
          } else null
        }

        @inline def readVertRate (isUp: Boolean): Double = {
          parseContent
          getNextContentString
          if (contentString == unavailable) NaN
          else {
            val vr = contentString.toDouble
            if (isUp) vr else -vr
          }
        }

        @inline def process_track = trackId = readStringContent

        @inline def process_time = date = readDateTimeContent

        @inline def process_tgtType = status |= readTargetTypeFlag

        @inline def process_tse = if (readIntContent == 1) status |= DroppedFlag  // track service ends

        @inline def process_aircraftId = acId = readInternedStringContent

        @inline def process_acType = acType = readACType

        @inline def process_altitude = altFt = readDoubleContent

        @inline def process_latitude = latDeg = readDoubleContent

        @inline def process_longitude = lonDeg = readDoubleContent

        @inline def process_heading = hdgDeg = readDoubleContent

        @inline def process_speed = spdMph = readDoubleContent

        @inline def process_vertRate = vertRate = readVertRate((status & UpFlag) != 0)

        @inline def process_di = if (readIntContent != 0)  status |= DisplayFlag

        @inline def process_ud = status |= readUpDownFlag

        @inline def process_gbs = if (readIntContent == 1) status |= OnGroundFlag

        //--- automatically generated
        @inline def match_t = len >= 1 && data(off) == 116
        @inline def match_track = len == 5 && data(off + 1) == 114 && data(off + 2) == 97 && data(off + 3) == 99 && data(off + 4) == 107
        @inline def match_time = len == 4 && data(off + 1) == 105 && data(off + 2) == 109 && data(off + 3) == 101
        @inline def match_tgtType = len == 7 && data(off + 1) == 103 && data(off + 2) == 116 && data(off + 3) == 84 && data(off + 4) == 121 && data(off + 5) == 112 && data(off + 6) == 101
        @inline def match_tse = len == 3 && data(off + 1) == 115 && data(off + 2) == 101
        @inline def match_a = len >= 1 && data(off) == 97
        @inline def match_aircraftId = len == 10 && data(off + 1) == 105 && data(off + 2) == 114 && data(off + 3) == 99 && data(off + 4) == 114 && data(off + 5) == 97 && data(off + 6) == 102 && data(off + 7) == 116 && data(off + 8) == 73 && data(off + 9) == 100
        @inline def match_acType = len == 6 && data(off + 1) == 99 && data(off + 2) == 84 && data(off + 3) == 121 && data(off + 4) == 112 && data(off + 5) == 101
        @inline def match_altitude = len == 8 && data(off + 1) == 108 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 117 && data(off + 6) == 100 && data(off + 7) == 101
        @inline def match_l = len >= 1 && data(off) == 108
        @inline def match_latitude = len == 8 && data(off + 1) == 97 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 117 && data(off + 6) == 100 && data(off + 7) == 101
        @inline def match_longitude = len == 9 && data(off + 1) == 111 && data(off + 2) == 110 && data(off + 3) == 103 && data(off + 4) == 105 && data(off + 5) == 116 && data(off + 6) == 117 && data(off + 7) == 100 && data(off + 8) == 101
        @inline def match_heading = len == 7 && data(off) == 104 && data(off + 1) == 101 && data(off + 2) == 97 && data(off + 3) == 100 && data(off + 4) == 105 && data(off + 5) == 110 && data(off + 6) == 103
        @inline def match_speed = len == 5 && data(off) == 115 && data(off + 1) == 112 && data(off + 2) == 101 && data(off + 3) == 101 && data(off + 4) == 100
        @inline def match_vertRate = len == 8 && data(off) == 118 && data(off + 1) == 101 && data(off + 2) == 114 && data(off + 3) == 116 && data(off + 4) == 82 && data(off + 5) == 97 && data(off + 6) == 116 && data(off + 7) == 101
        @inline def match_di = len == 2 && data(off) == 100 && data(off + 1) == 105
        @inline def match_ud = len == 2 && data(off) == 117 && data(off + 1) == 100
        @inline def match_gbs = len == 3 && data(off) == 103 && data(off + 1) == 98 && data(off + 2) == 115

        if (match_t) {
          if (match_track) {
            process_track
          } else if (match_time) {
            process_time
          } else if (match_tgtType) {
            process_tgtType
          } else if (match_tse) {
            process_tse
          }
        } else if (match_a) {
          if (match_aircraftId) {
            process_aircraftId
          } else if (match_acType) {
            process_acType
          } else if (match_altitude) {
            process_altitude
          }
        } else if (match_l) {
          if (match_latitude) {
            process_latitude
          } else if (match_longitude) {
            process_longitude
          }
        } else if (match_heading) {
          process_heading
        } else if (match_speed) {
          process_speed
        } else if (match_vertRate) {
          process_vertRate
        } else if (match_di) {
          process_di
        } else if (match_ud) {
          process_ud
        } else if (match_gbs) {
          process_gbs
        }

      } else { // end tag
        if (tag == positionReport) {
          // our minimal requirements are a dated lat/lon position and a trackId
          if (trackId != null && (date.isDefined)) {
            val track = createTrack(airportId,trackId,acId,latDeg,lonDeg,altFt,spdMph,hdgDeg,vertRate,date,status,acType)
            if (track != null) tracks += track
          }
          return // done
        }
      }
    }
  }

  //-- override these if we report full tracks (as opposed to deltas)
  protected def setAirport (ap: String) = ap

  protected def createTrack (airport: String, trackId: String, acId: String,
                             latDeg: Double, lonDeg: Double,
                             altFt: Double, spdMph: Double, hdgDeg: Double, vertRate: Double,
                             date: DateTime, status: Int, acType: String): AsdexTrack = {

    // if input values are defined, use those. Otherwise use the last value or the fallback if there was none
    val lat = if (isFinite(latDeg)) Degrees(latDeg) else UndefinedAngle
    val lon = if (isFinite(lonDeg)) Degrees(lonDeg) else UndefinedAngle

    if (lat.isDefined && lon.isDefined) {
      val alt = if (isFinite(altFt)) Feet(altFt) else UndefinedLength
      val hdg = if (isFinite(hdgDeg)) Degrees(hdgDeg) else UndefinedAngle
      val spd = if (isFinite(spdMph)) UsMilesPerHour(spdMph) else UndefinedSpeed
      val vr = if (isFinite(vertRate)) FeetPerMinute(vertRate) else UndefinedSpeed
      val cs = if (acId != null) getCallsign(acId,trackId) else trackId
      val act = if (acType != null) Some(acType) else None

      new AsdexTrack(trackId, cs, GeoPosition(lat, lon, alt), spd, hdg, vr, date, status, airport, act)

    } else null
  }

  def getCallsign (s: String, trackId: String) = if (s == "UNKN") trackId else s
}

/**
  * a AsdexMsgParser that only reports full AsdexTracks, i.e. stores partial updates until complete information
  * is available
  */
class FullAsdexMsgParser (_config: Config=NoConfig) extends AsdexMsgParser(_config) {

  //--- our cache to accumulate track infos over full/delta reports

  // TODO - this is not thread safe, turn lastTracks into a function argument
  var lastAirport: String = null // currently cached airport
  var lastTracks = new MutHashMap[String,AsdexTrack]

  override protected def setAirport (airportId: String) = {
    if (airportId != lastAirport) {
      lastAirport = airportId
      lastTracks.clear
    }
    airportId
  }

  @inline def fromDouble[A](d: Double, f: Double=>A, g: AsdexTrack=>A, fallback: A)(implicit last: Option[AsdexTrack]): A = {
    if (isFinite(d)) f(d) else withSomeOrElse(last,fallback)(g)
  }
  @inline def fromString[A](s: String, f: String=>A, g: AsdexTrack=>A, fallback: A)(implicit last: Option[AsdexTrack]): A = {
    if (s != null) f(s) else withSomeOrElse(last, fallback)(g)
  }

  // here we use the previously accumulated info to turn delta reports into full reports
  override protected def createTrack (airport: String, trackId: String, acId: String,
                                      latDeg: Double, lonDeg: Double,
                                      altFt: Double, spdMph: Double, hdgDeg: Double, vertRate: Double,
                                      date: DateTime, status: Int, acType: String): AsdexTrack = {
    implicit val last = lastTracks.get(trackId)

    // if input values are defined, use those. Otherwise use the last value or the fallback if there was none
    val lat = fromDouble(latDeg, Degrees, _.position.φ, UndefinedAngle)
    val lon = fromDouble(lonDeg, Degrees, _.position.λ, UndefinedAngle)
    val alt = fromDouble(altFt, Feet, _.position.altitude, UndefinedLength)
    val hdg = fromDouble(hdgDeg, Degrees, _.heading, UndefinedAngle)
    val spd = fromDouble(spdMph, UsMilesPerHour, _.speed, UndefinedSpeed)
    val vr = fromDouble(vertRate, FeetPerMinute, _.vr, UndefinedSpeed)
    val cs = fromString(acId, getCallsign(_,trackId), _.cs, trackId)
    val act = fromString(acType, Some(_), _.acType, None)
    var statusFlags = status
    var changedCS: Option[ChangedCS] = None

    ifSome(lastTracks.get(trackId)) { lastTrack =>
      if (acId != null && lastTrack.cs != cs) {
        statusFlags |= TrackedObject.ChangedCSFlag
        changedCS = Some(ChangedCS(lastTrack.cs))
      }
    }

    val track = new AsdexTrack(trackId, cs, GeoPosition(lat,lon,alt), spd, hdg, vr, date, statusFlags, airport, act)
    ifSome(changedCS) { track.amend }

    if (track.isDropped) {
      lastTracks -= trackId
    } else {
      lastTracks += trackId -> track
    }

    if (track.position.isDefined) track else null
  }
}

/**
  * a FullAsdexMsgParser that does not reset data when switching between airports. Can be used if
  * we need to compare AsdexTracks for different airports
  */
class GlobalFullAsdexMsgParser (_config: Config=NoConfig) extends FullAsdexMsgParser(_config) {

  val airports = new MutHashMap[String,MutHashMap[String,AsdexTrack]]

  override protected def setAirport (airportId: String) = {
    if (airportId != lastAirport) {
      lastAirport = airportId
      lastTracks = airports.getOrElseUpdate(airportId, new MutHashMap[String,AsdexTrack])
    }
    airportId
  }
}