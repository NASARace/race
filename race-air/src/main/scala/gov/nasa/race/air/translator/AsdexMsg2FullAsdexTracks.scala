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
import gov.nasa.race.air.{AsdexTrack, AsdexTrackType, VerticalDirection, AsdexTracks}
import gov.nasa.race.common.XmlParser
import gov.nasa.race.config._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer

/**
  * translator for SWIM ASDE-X asdexMsg messages to AirportTracks objects
  *
  * NOTE - asde-x track updates come in full- and delta- positionReports. This is the stateful translator version
  * that caches records in order to make sure we don't loose AsdexTrack values for delta-reports
  *
  * Tracks are cached on a per-airport basis, i.e. by changing the airport we reset the cache. The cache is also
  * time-stamped so that we can clear it if it has expired (no updates within configurable duration). This also avoids
  * loosing information for intermittent use (e.g. when zooming in/out of an airport in the viewer)
  */
class AsdexMsg2FullAsdexTracks(val config: Config=NoConfig) extends XmlParser[AsdexTracks] with ConfigurableTranslator {
  setBuffered(8192)

  

  //-- the XML messages we handle
  onStartElement = {
    case "asdexMsg" => asdexMsg
    case other => stopParsing
  }

  def asdexMsg = {
    var airport: String = null
    val tracks = new ArrayBuffer[AsdexTrack]

    whileNextElement {
      case "airport" => airport = readText
      case "positionReport" => positionReport(tracks)
    } {
      case "asdexMsg" => setResult(new AsdexTracks(airport,tracks))
      case _ => // ignore
    }
  }

  def positionReport (tracks: ArrayBuffer[AsdexTrack]): Unit = {
    var display = true
    var trackId: String = null
    var date: DateTime = null
    var lat, lon: Angle = UndefinedAngle
    var alt: Option[Length] = None
    var hdg: Option[Angle] = None
    var spd: Option[Speed] = None
    var drop: Boolean = false
    var tgtType = AsdexTrackType.Unknown
    var ud = VerticalDirection.Unknown
    var acId: Option[String] = None
    var acType: Option[String] = None
    var gbs: Boolean = false

    val fullReport = parseAttribute("full") && value == "true"

    whileNextElement {
      //--- start elements
      case "time" => date = DateTime.parse(readText())
      case "latitude" => lat = Degrees(readDouble())
      case "longitude" => lon = Degrees(readDouble())
      case "track" => trackId = readText()
      // apparently some messages have malformed elements such as <aircraftId r="1"/>
      case "aircraftId" => if (parseTrimmedText()) acId = Some(text)
      case "tgtType" => tgtType = readTgtType
      case "acType" => if (parseTrimmedText()) acType = Some(text)
      case "tse" => drop = readInt() == 1  // track service ends
      case "di" => display = readInt() != 0  // display
      case "ud" => ud = readVerticalDirection   // up/down
      case "gbs" => gbs = readInt() == 1 // ground bit (default false)
      case "altitude" => alt = Some(Feet(readDouble()))
      case "heading" => hdg = Some(Degrees(readDouble()))
      case "speed" => spd = Some(UsMilesPerHour(readDouble()))
      case _ => // ignored
    } {
      //--- end elements
      case "positionReport" =>
        if (display && (trackId != null) && lat.isDefined && lon.isDefined && (date != null)) {
          val track = new AsdexTrack(tgtType, trackId, date, LatLonPos(lat, lon), spd, hdg, drop, acId, acType, alt)
          tracks += track
        }
        return
      case _ => // ignore
    }
  }

  def readTgtType: AsdexTrackType.Value = {
    if (parseTrimmedText){
      text match {
        case "aircraft" => AsdexTrackType.Aircraft
        case "vehicle" => AsdexTrackType.Vehicle
        case _ => AsdexTrackType.Unknown
      }
    } else AsdexTrackType.Unknown
  }

  def readVerticalDirection: VerticalDirection.Value = {
    if (parseTrimmedText){
      text match {
        case "up" => VerticalDirection.Up
        case "down" => VerticalDirection.Down
        case _ => VerticalDirection.Unknown
      }
    } else VerticalDirection.Unknown
  }
}
