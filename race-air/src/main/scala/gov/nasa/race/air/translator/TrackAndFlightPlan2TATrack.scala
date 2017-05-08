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
import gov.nasa.race.air.TATrack
import gov.nasa.race.air.TATrack.Status
import gov.nasa.race.air.TATrack.Status.Status
import gov.nasa.race.common.XmlParser
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableTranslator
import gov.nasa.race.geo.{LatLonPos, XYPos}
import gov.nasa.race.uom.Angle.{Degrees, UndefinedAngle}
import gov.nasa.race.uom.Length.{Feet, NauticalMiles, UndefinedLength}
import gov.nasa.race.uom.Speed.{FeetPerSecond, Knots, UndefinedSpeed}
import gov.nasa.race.uom.{Angle, Length, Speed}
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * translates STDDS TAIS TATrackAndFlightPlan messages to TATrack objects
  */
class TrackAndFlightPlan2TATrack (val config: Config=null) extends XmlParser[Seq[TATrack]] with ConfigurableTranslator {

  val allowIncompleteTrack: Boolean = if (config != null) config.getBooleanOrElse("allow-incomplete", false) else false
  var tracks = new ArrayBuffer[TATrack](16)

  override def result = if (tracks.nonEmpty) Some(tracks) else None
  override def flatten = true

  //--- parse cache
  var src: String = _
  var trackNum: Int = -1
  var acAddress: String = _
  var beaconCode: String = _
  var mrtTime: DateTime = _
  var status: Status = Status.Undefined
  var lat,lon: Angle = UndefinedAngle
  var xPos,yPos: Length = UndefinedLength
  var vx,vy,vVert: Speed = UndefinedSpeed
  var isFrozen,isNew,isPseudo,isAdsb: Boolean = false
  var reportedAltitude: Length = UndefinedLength

  def resetTrackCache = {
    trackNum = -1
    acAddress = null
    beaconCode = null
    mrtTime = null
    status = Status.Undefined
    lat = UndefinedAngle; lon = UndefinedAngle
    xPos = UndefinedLength; yPos = UndefinedLength
    vx = UndefinedSpeed; vy = UndefinedSpeed; vVert = UndefinedSpeed
    isFrozen = false; isNew = false; isPseudo = false; isAdsb = false
    reportedAltitude = UndefinedLength
  }

  def addTrack = {
    // src, trackNum, x/yPos are all required by the schema
    if (src != null && trackNum != -1 && xPos.isDefined && yPos.isDefined) {
      if (allowIncompleteTrack || (mrtTime != null && vx.isDefined && vy.isDefined && reportedAltitude.isDefined)) {
        val spd = Speed.fromVxVy(vx,vy)
        val hdg = Angle.fromVxVy(vx,vy)
        val track = new TATrack(src,trackNum,XYPos(xPos,yPos),vVert,status,isFrozen,isNew,isPseudo,isAdsb,beaconCode,
          trackNum.toString,acAddress,LatLonPos(lat,lon),reportedAltitude,spd,hdg,mrtTime)

        tracks += track
      }
    }
  }

  def readBoolean = readInt == 1

  def readStatus = {
    readText match { // note that XSD requires lower case
      case "active" => Status.Active
      case "coasting" => Status.Coasting
      case "drop" => Status.Drop
    }
  }

  override def onStartElement = {
    case "TATrackAndFlightPlan" => tracks.clear
    case "src" => src = readText
    case "track" => resetTrackCache
    case "trackNum" => trackNum = readInt
    case "mrtTime" => mrtTime = DateTime.parse(readText)
    case "status" => status = readStatus
    case "xPos" => xPos = NauticalMiles(readInt / 256.0)
    case "yPos" => yPos = NauticalMiles(readInt / 256.0)
    case "lat" => lat = Degrees(readDouble)
    case "lon" => lon = Degrees(readDouble)
    case "vVert" => vVert = FeetPerSecond(readInt / 60.0)
    case "vx" => vx = Knots(readInt)
    case "vy" => vy = Knots(readInt)
    case "frozen" => isFrozen = readBoolean
    case "new" => isNew = readBoolean
    case "pseudo" => isPseudo = readBoolean
    case "adsb" => isAdsb = readBoolean
    case "reportedBeaconCode" => beaconCode = readText
    case "reportedAltitude" => reportedAltitude = Feet(readInt)

    case other => // ignore
  }

  override def onEndElement = {
    case "track" => addTrack

    case other => // ignore
  }

  def translate (src: Any): Option[Seq[TATrack]] = {
    src match {
      case xml: String if xml.nonEmpty => parse(xml)
      case Some(xml: String) if xml.nonEmpty => parse(xml)
      case other => None // nothing else supported yet
    }
  }
}
