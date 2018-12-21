/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
import gov.nasa.race.air.{FlightPlan, TATrack}
import gov.nasa.race.common.{Rev, Src, XmlParser}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config._
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom.Angle.{Degrees, UndefinedAngle}
import gov.nasa.race.uom.Length.{Feet, NauticalMiles, UndefinedLength}
import gov.nasa.race.uom.Speed.{FeetPerMinute, Knots, UndefinedSpeed}
import gov.nasa.race.uom.{Angle, Length, Speed}
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * STDDS TATrackAndFlightPlan to TATrack translator that uses stack frames to avoid the
  * cache reset problem
  */
class TATrackAndFlightPlan2TATrack (val config: Config=NoConfig) extends XmlParser[Seq[TATrack]] with ConfigurableTranslator {

  //--- configuration
  val allowIncompleteTrack: Boolean = if (config != null) config.getBooleanOrElse("allow-incomplete", false) else false
  val attachRev = config.getBooleanOrElse("attach-rev", false)
  val attachMsg = config.getBooleanOrElse("attach-msg", false)

  //--- our result data
  var tracks = new ArrayBuffer[TATrack](16)

  setBuffered(8192) // we will get a lot of these

  override def flatten = true // report as single tracks

  onStartElement = {
    case "TATrackAndFlightPlan" | "ns2:TATrackAndFlightPlan" => taTrackAndFlightPlan
    case other => stopParsing
  }

  def readBoolean = readInt == 1

  def readStatusFlag = {
    readText match { // note that XSD requires lower case
      case "active" => 0
      case "coasting" => TATrack.CoastingFlag
      case "drop" => TrackedObject.DroppedFlag
      case _ => 0
    }
  }

  def taTrackAndFlightPlan = {
    var src: String = null
    var stddsRev: Int = -1

    tracks.clear

    if (attachRev && parseAttribute("xmlns")) {
      if (value.contains("v3")) stddsRev = 3
      else if (value.contains("v2")) stddsRev = 2
      else stddsRev = -1
    }

    whileNextElement {
      case "src" => src = readText
      case "record" => record(src, stddsRev)
      case _ => // ignore
    }{
      case "TATrackAndFlightPlan" | "ns2:TATrackAndFlightPlan" => if (tracks.nonEmpty) setResult(tracks)
      case _ => // ignore
    }
  }

  def record (src: String, stddsRev: Int): Unit = {
    var trackId: String = null
    var acId: String  = null
    var beaconCode: String = null
    var mrtTime: DateTime = null
    var lat,lon: Angle = UndefinedAngle
    var xPos,yPos: Length = UndefinedLength
    var vx,vy,vVert: Speed = UndefinedSpeed
    var status: Int = 0
    var reportedAltitude: Length = UndefinedLength
    var flightPlan: Option[FlightPlan] = None

    whileNextElement {
      case "trackNum" => trackId = readText
      case "mrtTime" => mrtTime = DateTime.parse(readText)
      case "xPos" => xPos = NauticalMiles(readInt / 256.0)
      case "yPos" => yPos = NauticalMiles(readInt / 256.0)
      case "lat" => lat = Degrees(readDouble)
      case "lon" => lon = Degrees(readDouble)
      case "vVert" => vVert = FeetPerMinute(readInt)
      case "vx" => vx = Knots(readInt)
      case "vy" => vy = Knots(readInt)

      case "status" => status |= readStatusFlag
      case "frozen" => if (readBoolean) status |= TrackedObject.FrozenFlag
      case "new" => if (readBoolean) status |= TrackedObject.NewFlag
      case "pseudo" => if (readBoolean) status |= TATrack.PseudoFlag
      case "adsb" => if (readBoolean) status |= TATrack.AdsbFlag

      case "reportedBeaconCode" => beaconCode = readText
      case "reportedAltitude" => reportedAltitude = Feet(readInt)
      case "flightPlan" => flightPlan = Some(new FlightPlan) // just a placeholder for now
      case "acid" => acId = readText // part of the flight plan but we extract it no matter what

      case _ => // ignore
    } {
      case "record" =>
        // src, trackNum, x/yPos are all required by the schema
        if (src != null && trackId != null && xPos.isDefined && yPos.isDefined) {
          if (allowIncompleteTrack || (mrtTime != null && vx.isDefined && vy.isDefined && reportedAltitude.isDefined)) {
            val spd = Speed.fromVxVy(vx, vy)
            val hdg = Angle.fromVxVy(vx, vy)
            if (acId == null) acId = trackId

            val track = new TATrack(trackId,acId,GeoPosition(lat,lon,reportedAltitude),hdg,spd,vVert,mrtTime,status,
                                    src, XYPos(xPos, yPos), beaconCode, flightPlan)

            if (attachRev && stddsRev >= 0) track.amend(Rev(3, stddsRev.toShort))
            if (attachMsg) track.amend(Src(msg))

            tracks += track
          }
        }
        return
      case _ => // ignore
    }
  }
}
