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
import gov.nasa.race.air.{AsdexTrack, AsdexTrackType, AsdexTracks}
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
  */
class AsdexMsg2AsdexTracks(val config: Config=NoConfig) extends XmlParser[AsdexTracks]
                                                        with ConfigurableTranslator {
  setBuffered(8192)

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
    var tgtType = AsdexTrackType.UNKNOWN
    var acId: Option[String] = None
    var acType: Option[String] = None

    whileNextElement {
      case "time" => date = DateTime.parse(readText())
      case "latitude" => lat = Degrees(readDouble())
      case "longitude" => lon = Degrees(readDouble())
      case "track" => trackId = readText()
      // apparently some messages have malformed elements such as <aircraftId r="1"/>
      case "aircraftId" => if (parseTrimmedText()) acId = Some(text)
      case "tgtType" => if (parseTrimmedText() && text == "aircraft") tgtType = AsdexTrackType.AIRCRAFT
      case "acType" => if (parseTrimmedText()) acType = Some(text)
      case "tse" => drop = readInt() == 1
      case "di" => display = readInt() != 0
      case "altitude" => alt = Some(Feet(readDouble()))
      case "heading" => hdg = Some(Degrees(readDouble()))
      case "speed" => spd = Some(UsMilesPerHour(readDouble()))
      case _ => // ignored
    } {
      case "positionReport" =>
        if (display && (trackId != null) && lat.isDefined && lon.isDefined && (date != null)) {
          val track = new AsdexTrack(tgtType, trackId, date, LatLonPos(lat, lon), spd, hdg, drop, acId, acType, alt)
          tracks += track
        }
        return
      case _ => // ignore
    }
  }
}
