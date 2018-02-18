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
import gov.nasa.race.air.{TFMTrack, TFMTracks}
import gov.nasa.race.config._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.util.XmlPullParser
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import gov.nasa.race.track.TrackedObject
import org.joda.time.DateTime


/**
  * translator from <ds:tfmDataService> messages to TFMTracks objects
  */
class TfmDataService2TFMTracks(val config: Config=NoConfig) extends XmlPullParser with ConfigurableTranslator {
  setBuffered(8192)

  def translate(src: Any): Option[TFMTracks] = {
    src match {
      case xml: String if xml.nonEmpty => translateText(xml)
      case Some(xml:String) if xml.nonEmpty => translateText(xml)
      case other => None // nothing else supported yet
    }
  }

  def translateText(s: String): Option[TFMTracks] = {
    initialize(s.toCharArray)

    var flightRef: String = "?"
    var cs: String = null
    var source: String = "?"
    var date: DateTime = null
    var lat: Angle = UndefinedAngle
    var lon: Angle = UndefinedAngle
    var speed: Speed = UndefinedSpeed
    var alt: Length = UndefinedLength
    var nextWP: LatLonPos = null
    var nextWPDate: DateTime = null
    var completed: Boolean = false

    var isTrackInfo = false
    var tracks = Seq[TFMTrack]()

    def resetVars = {
      flightRef="?"; cs=null; source="?"; completed = false
      date=null; lat=UndefinedAngle; lon=UndefinedAngle; speed=UndefinedSpeed; alt=UndefinedLength; nextWP=null; nextWPDate=null
    }

    // <2do> this probably can be relaxed - we have useful info even without nextWP
    def checkVars = {
      cs != null && (completed ||
                     (date != null && lat.isDefined && lon.isDefined && alt.isDefined && nextWP != null) )
    }

    def readDMS: Angle = {
      val deg = readIntAttribute("degrees")
      val dir = readAttribute("direction")
      val min = readIntAttribute("minutes")
      val sec = if (parseAttribute("seconds")) value.toInt else 0
      val d = deg.toDouble + min.toDouble/60.0 + sec.toDouble/3600.0
      if (dir == "WEST" || dir == "SOUTH") Degrees(-d) else Degrees(d)
    }

    def readAlt: Length = {
      val t = text
      val len = t.length
      t.last match {
        case 'T' => Feet( 1000.0 * t.substring(0,len-1).toInt)
        case 'C' => Feet( 100.0 * t.substring(0,len-1).toInt)
        case other => Feet( 100.0 * t.toInt)
      }
    }

    try {
      while (parseNextElement()) {
        if (isStartElement) {
          tag match {
            case "fdm:fltdMessage" => if (parseAttribute("flightRef")) flightRef = value
            case "fdm:trackInformation" => isTrackInfo = true
            case "nxce:facilityIdentifier" if isTrackInfo => if (parseTrimmedText()) source = text
            case "nxce:aircraftId" => if (parseTrimmedText()) cs = text
            case "nxcm:speed" if isTrackInfo =>
              // watch out, there are also nested "speed" elements that don't have actual values
              if (parseTrimmedText()) speed = UsMilesPerHour(text.toDouble)
            case "nxce:simpleAltitude" if isTrackInfo & hasSomeParent("nxcm:reportedAltitude") =>
              if (parseTrimmedText()) alt = readAlt
            case "nxcm:timeAtPosition" if isTrackInfo => date = DateTime.parse(readText())
            case "nxce:latitudeDMS" if isTrackInfo => lat = readDMS
            case "nxce:longitudeDMS" if isTrackInfo => lon = readDMS

            //--- completion
            case "nxce:airport" if hasParent("nxce:arrivalPoint") =>
              val airportId = readText()
              // we could get the airport location here
            case "nxcm:eta" if hasParent("nxcm:airlineData") =>
              if (readAttribute("etaType") == "ACTUAL") {
                if (date == null) date = DateTime.parse(readAttribute("timeValue"))
                if (lat.isUndefined) lat = Angle0
                if (lon.isUndefined) lon = Angle0
              }
            case "nxcm:flightStatus" => completed = "COMPLETED" == readText()

            case "nxcm:nextEvent" if isTrackInfo & hasParent("nxcm:ncsmTrackData") =>
              var lat = readDoubleAttribute("latitudeDecimal")
              var lon = readDoubleAttribute("longitudeDecimal")
              nextWP = LatLonPos.fromDegrees(lat,lon)
            case "nxcm:eta" if isTrackInfo & hasParent("nxcm:ncsmTrackData") =>
              nextWPDate = DateTime.parse(readAttribute("timeValue"))
            case other => // ignore
          }
        } else { // end element
          tag match {
            case "fdm:trackInformation" => isTrackInfo = false
            case "fdm:fltdMessage" =>
              if (checkVars) {
                val status = if (completed) TrackedObject.CompletedFlag else TrackedObject.TrackNoStatus
                val track = if (completed) {
                  TFMTrack(flightRef,cs,LatLonPos(lat,lon),alt,speed,date,status,
                           source,None,None)
                } else {
                  TFMTrack(flightRef,cs,LatLonPos(lat,lon),alt,speed,date,status,
                           source,Some(nextWP),Some(nextWPDate))
                }
                tracks = track  +: tracks
                resetVars
              }
            case "ds:tfmDataService" => if (tracks.nonEmpty) return Some(TFMTracks(tracks))
            case other => // ignore
          }
        }
      }
    } catch {
      case t: Throwable => t.printStackTrace()
    }
    None
  }
}
