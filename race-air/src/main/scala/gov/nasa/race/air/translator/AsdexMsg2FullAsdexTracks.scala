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
import gov.nasa.race._
import gov.nasa.race.air.{AsdexTrack, AsdexTrackType, VerticalDirection, AsdexTracks}
import gov.nasa.race.common.XmlParser
import gov.nasa.race.config._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import scala.Double.{NaN}
import java.lang.Double.{isFinite}
import org.joda.time.DateTime
import scala.collection.mutable.{ArrayBuffer,HashMap}

/**
  * translator for SWIM ASDE-X asdexMsg messages to AirportTracks objects
  *
  * NOTE - asde-x track updates come in full- and delta- positionReports. This is the stateful translator version
  * that caches records in order to make sure we don't loose AsdexTrack values for delta-reports
  *
  * Tracks are cached on a per-airport basis, i.e. by changing the airport we reset the cache. The cache is also
  * time-stamped so that we can clear it if it has expired (no updates within configurable duration). This also avoids
  * loosing information for intermittent use (e.g. when zooming in/out of an airport in the viewer)
  *
  * This implementation makes heavy use of special values (null, NaN) for optional fields and hence is not very
  * scalatic in order to avoid heap pressure (this is a high volume data stream)
  */
class AsdexMsg2FullAsdexTracks(val config: Config=NoConfig) extends XmlParser[AsdexTracks] with ConfigurableTranslator {
  setBuffered(8192)

  //--- our cache to accumulate track infos over full/delta reports
  var lastAirport: String = null // currently cached airport
  var lastUpdate: Long = 0 // wall time epoch of last update
  val lastTracks = new HashMap[String,AsdexTrack]


  //-- the XML messages we handle
  onStartElement = {
    case "asdexMsg" => asdexMsg
    case other => stopParsing
  }

  def asdexMsg = {
    var airport: String = null
    val tracks = new ArrayBuffer[AsdexTrack]

    whileNextElement {
      case "airport" =>
        airport = readText
        if (airport != lastAirport) {
          lastAirport = airport
          lastTracks.clear
        }
      case "positionReport" => positionReport(tracks)
    } {
      case "asdexMsg" => setResult(new AsdexTracks(airport,tracks))
      case _ => // ignore
    }
  }

  def positionReport (tracks: ArrayBuffer[AsdexTrack]): Unit = {
    // note that we have to use different values for optionals that might not be in a delta update so that
    // we can distinguish from cached values
    var display = true
    var trackId: String = null
    var date: DateTime = null
    var latDeg, lonDeg: Double = NaN
    var altFt: Double = NaN
    var hdgDeg: Double = NaN
    var spdMph: Double = NaN
    var drop: Boolean = false
    var tgtType: String = null
    var ud: String = null
    var acId: String = null
    var acType: String = null
    var gbs: Boolean = false

    val fullReport = parseAttribute("full") && value == "true"

    whileNextElement {
      //--- start elements
      case "track" => trackId = readText()
      case "time" => date = DateTime.parse(readText())
      case "latitude" => latDeg = readDouble
      case "longitude" => lonDeg = readDouble
      // apparently some messages have malformed elements such as <aircraftId r="1"/>

      case "tse" => drop = readInt() == 1  // track service ends
      case "di" => display = readInt() != 0  // display
      case "ud" => ud = readText   // up/down
      case "gbs" => gbs = readInt() == 1 // ground bit (default false)

      case "aircraftId" => acId = readText
      case "tgtType" => tgtType = readText
      case "acType" => acType = readText
      case "altitude" => altFt = readDouble
      case "heading" => hdgDeg = readDouble
      case "speed" => spdMph = readDouble
      case _ => // ignored
    } {
      //--- end elements
      case "positionReport" =>
        // our minimal requirements are a dated lat/lon position and a trackId
        if (display && trackId != null && (date != null)) {
          val last = lastTracks.get(trackId)
          val track = if (last.isDefined) {
            val lastTrack = last.get
            if (latDeg.isNaN) latDeg = lastTrack.pos.φ.toDegrees // sometimes we get only one coordinate
            if (lonDeg.isNaN) lonDeg = lastTrack.pos.λ.toDegrees
            val cs = if (acId == null) lastTrack.acId.orElse(Some(trackId)) else Some(acId)
            val tt = if (tgtType == null) lastTrack.trackType else getTrackType(tgtType)
            val alt = if (altFt.isNaN) lastTrack.altitude else Some(Feet(altFt))
            val spd = if (spdMph.isNaN) lastTrack.speed else Some(UsMilesPerHour(spdMph))
            val hdg = if (hdgDeg.isNaN) lastTrack.heading else Some(Degrees(hdgDeg))
            val act = if (acType == null) lastTrack.acType else Some(acType)
            new AsdexTrack(tt, trackId, date, LatLonPos.fromDegrees(latDeg,lonDeg), spd, hdg, drop, cs, act, alt)
          } else {
            val cs = if (acId == null) Some(trackId) else Some(acId)
            val tt = if (tgtType == null) AsdexTrackType.Unknown else getTrackType(tgtType)
            val alt = if (altFt.isNaN) None else Some(Feet(altFt))
            val spd = if (spdMph.isNaN) None else Some(UsMilesPerHour(spdMph))
            val hdg = if (hdgDeg.isNaN) None else Some(Degrees(hdgDeg))
            val act = if (acType == null) None else Some(acType)
            new AsdexTrack(tt, trackId, date, LatLonPos.fromDegrees(latDeg,lonDeg), spd, hdg, drop, cs, act, alt)
          }

          tracks += track
          lastTracks += trackId -> track
        }
        return // done
      case _ => // ignore
    }
  }

  def getTrackType (tt: String) = {
    tt match {
      case "aircraft" => AsdexTrackType.Aircraft
      case "vehicle" => AsdexTrackType.Vehicle
      case _ => AsdexTrackType.Unknown
    }
  }

  def getVerticalDirection (ud: String) = {
    ud match {
      case "up" => VerticalDirection.Up
      case "down" => VerticalDirection.Down
      case _ => VerticalDirection.Unknown
    }
  }
}
