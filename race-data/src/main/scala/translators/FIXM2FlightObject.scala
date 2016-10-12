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
  extends FIXMParser[Seq[IdentifiableAircraft]] with ConfigurableTranslator {

  //--- our parser value cache
  var id, cs: String = _
  var lat, lon, vx, vy: Double = _
  var alt: Length = _
  var spd: Velocity = _
  var date, arrivalDate: DateTime = _
  var arrivalPoint: String = _
  resetCache

  def resetCache = {
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
        if (isDefined(lat) && isDefined(lon) && isDefined(date) &&
            isDefined(vx) && isDefined(vy) && isDefined(spd) && isDefined(alt)) {
          flights += FlightPos(id, cs, LatLonPos(Degrees(lat), Degrees(lon)),
                               alt, spd, Degrees(Math.atan2(vx, vy).toDegrees), date)
        }
      }
    }
  }

  protected var flights = new ArrayBuffer[IdentifiableAircraft](20)
  def resetFlights = flights.clear()

  override def result = Some(flights)
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
    case "flight" => resetCache  // only in new (batched) format
    case "ns5:NasFlight" =>  // old (1min) update format
      resetFlights
      resetCache

    case "flightIdentification" => parseAllAttributes {
      case "computerId" => id = value
      case "aircraftIdentification" => cs = value
      case other => // ignored
    }

    //--- enRoute info
    case "position" if hasParent("enRoute") =>
      if (parseAttribute("positionTime")) date = DateTime.parse(value)
    case "pos" if hasParent("location") =>
      lat = readDouble
      lon = readNextDouble
    case "x" => vx = readDouble // we just need it for heading computation and assume same 'uom'
    case "y" => vy = readDouble
    case "surveillance" if hasParent("actualSpeed") => spd = readSpeed
    case "altitude" => alt = readAltitude

    //--- completed flights
    case "arrival" => if (parseAttribute("arrivalPoint")) arrivalPoint = value
    case "actual" if hasSomeParent("arrival") => arrivalDate = DateTime.parse(readAttribute("time"))

    case other =>  // ignore
  }

  override def onEndElement = {
    case "flight" => addFlightObject  // new (12sec) update format
    case "ns5:NasFlight" => addFlightObject // old (1min) update format
    case other =>
  }
}