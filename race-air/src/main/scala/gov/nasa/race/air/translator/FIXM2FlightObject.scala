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
package gov.nasa.race.air.translator

import com.typesafe.config.Config
import gov.nasa.race.air.{FlightCompleted, FlightPos, IdentifiableAircraft}
import gov.nasa.race.config.ConfigurableTranslator
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.common._
import org.joda.time.DateTime
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._

import scala.collection.mutable.ArrayBuffer

/**
  * the 12sec update (Solace) SFDPS translator
  */
class FIXM2FlightObject (val config: Config=null)
                 extends FIXMParser[Seq[IdentifiableAircraft]] with ConfigurableTranslator {
  setBuffered(4096)

  //--- our parser value cache
  var id, cs: String = null
  var lat, lon, vx, vy: Double = UndefinedDouble
  var alt: Length = UndefinedLength
  var spd: Speed = UndefinedSpeed
  var date, arrivalDate: DateTime = null
  var arrivalPoint: String = null
  resetCache

  def resetCache = {
    id = null
    cs = null
    lat = UndefinedDouble
    lon = UndefinedDouble
    vx = UndefinedDouble
    vy = UndefinedDouble
    alt = UndefinedLength
    spd = UndefinedSpeed
    date = null
    arrivalDate = null
    arrivalPoint = null
  }

  // TODO - this needs error reporting
  def addFlightObject = {
    if (cs != null) {
      if (arrivalDate != null) {
        flights += FlightCompleted(id, cs, arrivalPoint, arrivalDate)
      } else {
        if (lat.isDefined && lon.isDefined && date != null &&
            vx.isDefined && vy.isDefined && spd.isDefined && alt.isDefined) {
          flights += new FlightPos(id, cs, LatLonPos(Degrees(lat), Degrees(lon)),
                                   alt, spd, Degrees(Math.atan2(vx, vy).toDegrees), date)
        } else {
          //println(s"@@@ $lat $lon $date $vx $vy $spd $alt")
        }
      }
    }
  }

  protected var flights = new ArrayBuffer[IdentifiableAircraft](20)
  def resetFlights = flights.clear()

  override def flatten = true

  //--- translation

  onStartElement = {
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

  onEndElement = {
    case "ns5:MessageCollection" => if (flights.nonEmpty) setResult(flights)
    case "flight" => addFlightObject  // new (12sec) update format
    case "ns5:NasFlight" => // old (1min) update format - one flight per message
      addFlightObject
      if (flights.nonEmpty) setResult(flights)
    case other =>
  }
}