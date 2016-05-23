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
import gov.nasa.race.data.{IdentifiableAircraft, FlightCompleted, LatLonPos, FlightPos}
import com.github.nscala_time.time.Imports._
import squants.space._
import squants.motion._

/**
 * translator from sfdps NasFlight XML messages (FIXM) to FlightPos objects
 */
class NasFlight2FlightObject(val config: Config=null) extends XmlPullParser with ConfigurableTranslator {

  def translate (src: Any): Option[IdentifiableAircraft] = {
    src match {
      case xml: String if xml.nonEmpty => translateText(xml)
      case Some(xml: String) if xml.nonEmpty => translateText(xml)
      case other => None // nothing else supported yet
    }
  }

  def translateText (s: String): Option[IdentifiableAircraft] = {
    initialize(s.toCharArray)
    var id = ""; var cs = "";
    var vx = 0d; var vy = 0d;
    var lon = 0d; var lat = 0d;
    var spd: Velocity = null
    var alt: Length = null
    var date: DateTime = null

    var arrivalPoint = ""
    var arrivalDate: DateTime = null

    try {
      while(parseNextElement()){
        if (isStartElement){
          tag match {
            case "arrival" => if (parseAttribute("arrivalPoint")) arrivalPoint = value
            case "actual" if (arrivalPoint != null) & hasSomeParent("arrival") => arrivalDate = DateTime.parse(readAttribute("time"))
            case "flightIdentification" =>
              id = readAttribute("computerId") // <2do> do we use this or the flight plan identifier ?
              cs = readAttribute("aircraftIdentification")
            //case "flightStatus" => status = readAttribute("fdpsFlightStatus")  // redundant, we should have an arrivalDate
            //case "flightPlan" => id = readAttribute("identifier")
            case "surveillance" if hasParent("actualSpeed") => spd = readSpeed
            case "altitude" => alt = readAltitude
            case "pos" if hasParent("location") =>
              lat = readDouble
              lon = readNextDouble
            case "x" => vx = readDouble // we don't need uom here since x and y use the same
            case "y" => vy = readDouble
            case "position" if hasParent("enRoute") =>
              if (parseAttribute("positionTime")) date = DateTime.parse(value)
            case _ => // ignore others
          }
        } // ignore EndElements
      }

      if (arrivalDate != null) { // completed
        Some( FlightCompleted( id, cs, arrivalPoint, arrivalDate))

      } else {
        if (cs.nonEmpty && date != null && alt != null) { // valid enroute
          Some( FlightPos( id, cs, LatLonPos(Degrees(lat), Degrees(lon)),
                           alt, spd, Degrees(Math.atan2(vx, vy).toDegrees), date))
        } else { // insufficient data
          None
        }
      }

    } catch {
      case t: Throwable =>
        t.printStackTrace()
        None
    }
  }

  // <2do> check UNECE codes and FAA defaults

  def readSpeed: Velocity = {
    val uom = if (parseAttribute("uom")) value else ""
    val v = readDouble
    uom match {
      case "KNOTS" => Knots(v)
      case "MPH" => UsMilesPerHour(v)
      case "KMH" => KilometersPerHour(v)
      case _ => Knots(v)
    }
  }

  def readAltitude: Length = {
    val uom = if (parseAttribute("uom")) value else ""
    val v = readDouble
    uom match {
      case "FEET" => Feet(v)
      case "METERS" => Meters(v)
      case _ => Feet(v)
    }
  }
}
