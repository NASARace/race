/*
 * Copyright (c) 2016, United States Government, as represented by the
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
package gov.nasa.race.data.translators

import com.typesafe.config.Config
import gov.nasa.race.common.{ConfigurableTranslator, XmlParser}
import gov.nasa.race.data._
import gov.nasa.race.data.{FlightCompleted, FlightPos, IdentifiableAircraft, LatLonPos}
import org.joda.time.DateTime
import squants.motion.{KilometersPerHour, Knots, UsMilesPerHour, Velocity}
import squants.space.{Degrees, Feet, Length, Meters}
import squants.{Length, Velocity}

import scala.collection.mutable.ArrayBuffer

/**
  * the 12sec update (Solace) SFDPS translator
  */
class FIXM2FlightObject (val config: Config=null)
      extends XmlParser[Seq[IdentifiableAircraft]] with ConfigurableTranslator {

  protected class Vars {
    var id, cs = Undefined[String]
    var lat, lon, vx, vy = UndefinedDouble
    var alt = Undefined[Length]
    var spd = Undefined[Velocity]
    var date = Undefined[DateTime]
    var arrivalDate = Undefined[DateTime]
    var arrivalPoint = Undefined[String]

    def reset = {
      id = Undefined[String]
      cs = Undefined[String]
      lat = UndefinedDouble
      lon = UndefinedDouble
      vx = UndefinedDouble
      vy = UndefinedDouble
      alt = Undefined[Length]
      spd = Undefined[Velocity]
      date = Undefined[DateTime]
      arrivalDate = Undefined[DateTime]
      arrivalPoint = Undefined[String]
    }

    // TODO - this needs error reporting
    def addFlightObject = {
      if (cs != null) {
        if (isDefined(arrivalDate)) {
          flights += FlightCompleted(id, cs, arrivalPoint, arrivalDate)
        } else {
          if (isDefined(lat) && isDefined(lon) &&
              isDefined(vx) && isDefined(vy) &&
              isDefined(spd) && isDefined(alt) && isDefined(date)) {
            flights += FlightPos(id, cs, LatLonPos(Degrees(lat), Degrees(lon)),
              alt, spd, Degrees(Math.atan2(vx, vy).toDegrees), date)
          }
        }
      }
    }
  }
  protected var v = new Vars

  protected var flights = new ArrayBuffer[IdentifiableAircraft](20)
  def resetFlights = flights.clear()

  val result = Some(flights)
  override def flatten = true

  //--- translation

  def translate(src: Any): Option[Seq[IdentifiableAircraft]] = {
    src match {
      case xml: String if xml.nonEmpty => parse(xml)
      case Some(xml: String) if xml.nonEmpty => parse(xml)
      case other => None // nothing else supported yet
    }
  }

  override def onStartElement = {
    case "ns5:MessageCollection" => resetFlights  // 12sec update format (Solace)
    case "ns5:NasFlight" => resetFlights // old (1min) update format
    case "flight" => v.reset  // only in new (batched) format

    case "flightIdentification" => parseAllAttributes {
      case "computerId" => v.id = value
      case "aircraftIdentification" => v.cs = value
      case other => // ignored
    }

    //--- enRoute info
    case "position" if hasParent("enRoute") =>
      if (parseAttribute("positionTime")) v.date = DateTime.parse(value)
    case "pos" if hasParent("location") =>
      v.lat = readDouble
      v.lon = readNextDouble
    case "x" => v.vx = readDouble // we just need it for heading computation and assume same 'uom'
    case "y" => v.vy = readDouble
    case "surveillance" if hasParent("actualSpeed") => v.spd = readSpeed
    case "altitude" => v.alt = readAltitude

    //--- completed flights
    case "arrival" => if (parseAttribute("arrivalPoint")) v.arrivalPoint = value
    case "actual" if hasSomeParent("arrival") => v.arrivalDate = DateTime.parse(readAttribute("time"))

    case other =>  // ignore
  }

  override def onEndElement = {
    case "flight" => v.addFlightObject  // new (12sec) update format
    case "ns5:NasFlight" => v.addFlightObject // old (1min) update format
    case other =>
  }

  //--- helpers

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