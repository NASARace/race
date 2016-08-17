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

package gov.nasa.race.data.translators

import com.typesafe.config.Config
import gov.nasa.race.common.{ConfigurableTranslator, XmlPullParser}
import gov.nasa.race.data.{TrackType, LatLonPos, Track, AirportTracks}
import org.joda.time.DateTime
import squants.motion.{UsMilesPerHour, Velocity}
import squants.{Length, Angle}
import squants.space.{Feet, Degrees}

/**
  * translator for SWIM ASDE-X asdexMsg messages to AirportTracks objects
  */
class AsdexMsg2AirportTracks (val config: Config=null) extends XmlPullParser with ConfigurableTranslator {

  def translate(src: Any): Option[AirportTracks] = {
    src match {
      case xml: String if xml.nonEmpty => translateText(xml)
      case Some(xml:String) if xml.nonEmpty => translateText(xml)
      case other => None // nothing else supported yet
    }
  }

  def translateText(s: String): Option[AirportTracks] = {
    initialize(s.toCharArray)
    var airport: String = null
    var tracks = Seq[Track]()

    var display = true
    var trackId: String = null
    var date: DateTime = null
    var lat, lon: Angle = null
    var alt: Option[Length] = None
    var hdg: Option[Angle] = None
    var spd: Option[Velocity] = None
    var drop: Boolean = false
    var tgtType = TrackType.UNKNOWN
    var acId: Option[String] = None
    var acType: Option[String] = None

    def resetTrackVars = {
      display = true; trackId = null; lat = null; lon = null; date = null; tgtType = TrackType.UNKNOWN
      alt = None; spd = None; hdg = None; drop = false; acId = None; acType = None
    }

    try {
      while (parseNextElement()) {
        if (isStartElement) {
          tag match {
              // <2do> check if we need to check for paths
            case "airport" => airport = readText()
            case "time" => date = DateTime.parse(readText())
            case "latitude" => lat = Degrees(readDouble())
            case "longitude" => lon = Degrees(readDouble())
            case "track" => trackId = readText()
              // apparently some messages have malformed elements such as <aircraftId r="1"/>
            case "aircraftId" => if (parseTrimmedText()) acId = Some(text)
            case "tgtType" => if (parseTrimmedText() && text == "aircraft") tgtType = TrackType.AIRCRAFT
            case "acType" => if (parseTrimmedText()) acType = Some(text)
            case "tse" => drop = readInt() == 1
            case "di" => display = readInt() != 0
            case "altitude" => alt = Some(Feet(readDouble()))
            case "heading" => hdg = Some(Degrees(readDouble()))
            case "speed" => spd = Some(UsMilesPerHour(readDouble()))
            case _ => // ignored
          }
        } else {  // end element
          tag match {
            case "positionReport" =>
              if (display && trackId != null && lat != null && lon != null && date != null) {
                val track = new Track(tgtType, trackId, date, LatLonPos(lat, lon), spd, hdg, drop, acId, acType, alt)
                tracks = track +: tracks
              }
              resetTrackVars

            case "asdexMsg" => return Some( new AirportTracks(airport,tracks))
            case _ => // ignored
          }
        }
      }
    } catch {
      case t: Throwable => t.printStackTrace()
    }
    None
  }
}
