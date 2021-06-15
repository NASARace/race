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

package gov.nasa.race.air

import akka.actor.ExtendedActorSystem
import gov.nasa.race.common.{AllId, NoneId}
import gov.nasa.race.core.SingleTypeAkkaSerializer
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.uom._

import scala.collection.immutable.SortedMap
import scala.util.matching.Regex

/**
  * list of airports, with respective ASDE-X support as of 09/16
  *
  * TODO - verify against http://www.airnav.com/airport/<ID>
  *
  * TODO - since this is dynamic data we might want to turn this into a config file that can be updated
  * without recompilation
  */
object Airport {
  def apply (id: String, name: String, city: String, lat: Double, lon: Double, hasAsdex: Boolean = true) = {
    new Airport(id,name,city,GeoPosition.fromDegrees(lat,lon),hasAsdex)
  }

  val KATL = new Airport("KATL", "Hartsfield - Jackson Atlanta International", "Atlanta", GeoPosition.fromDegreesAndFeet(33.636667, -84.428056, 1027), true)
  val KORD = new Airport("KORD", "Chicago O'Hare International", "Chicago", GeoPosition.fromDegreesAndFeet(41.978611, -87.904722, 668), true)
  val KLAX = new Airport("KLAX", "Los Angeles International",  "Los Angeles",  GeoPosition.fromDegreesAndFeet(33.9425, -118.408056, 125), true)
  val KDFW = new Airport("KDFW", "Dallas/Fort Worth International", "Dallas/Fort Worth", GeoPosition.fromDegreesAndFeet(32.896944, -97.038056, 607), true)
  val KPHX = new Airport("KPHX", "Phoenix Sky Harbor International", "Phoenix", GeoPosition.fromDegreesAndFeet(33.4373, -112.0078, 1134.6), true)
  val KDEN = new Airport("KDEN", "Denver International", "Denver", GeoPosition.fromDegreesAndFeet(39.861667, -104.673056, 5433.8), true)
  val KLAS = new Airport("KLAS", "Mc Carran International", "Las Vegas",  GeoPosition.fromDegreesAndFeet(36.08, -115.152222, 2181), true)
  val KIAH = new Airport("KIAH", "George Bush International", "Houston", GeoPosition.fromDegreesAndFeet(29.9902, -95.3368, 96.4), true)
  val KMSP = new Airport("KMSP", "Minneapolis-St Paul International", "Minneapolis", GeoPosition.fromDegreesAndFeet(44.8848, -93.2223, 841.8), true)
  val KDTW = new Airport("KDTW", "Detroit Metropolitan Wayne International", "Detroit", GeoPosition.fromDegreesAndFeet(42.2162, -83.3554, 645.2), true)
  val KJFK = new Airport("KJFK", "John F Kennedy International", "New York", GeoPosition.fromDegreesAndFeet(40.639722, -73.778889, 12.7), true)
  val KSFO = new Airport("KSFO", "San Francisco International", "San Francisco",  GeoPosition.fromDegreesAndFeet(37.618889, -122.375, 13), true)
  val KEWR = new Airport("KEWR", "Newark International", "Newark", GeoPosition.fromDegreesAndFeet(40.6895, -74.1745, 17.4), true)
  val KMIA = new Airport("KMIA", "Miami International", "Miami", GeoPosition.fromDegreesAndFeet(25.7959, -80.2870, 8.5), true)
  val KMCO = new Airport("KMCO", "Orlando International", "Orlando", GeoPosition.fromDegreesAndFeet(28.4312, -81.3081, 96.4), true)
  val KSEA = new Airport("KSEA", "Seattle-Tacoma International", "Seattle", GeoPosition.fromDegreesAndFeet(47.4502, -122.3088, 432.5), true)
  val KSTL = new Airport("KSTL", "Lambert-St Louis International", "St Louis", GeoPosition.fromDegreesAndFeet(38.7431, -90.3651, 618.0), true)
  val KPHL = new Airport("KPHL", "Philadelphia International", "Philadelphia", GeoPosition.fromDegreesAndFeet(39.8744, -75.2424, 36.1), true)
  val KCLT = new Airport("KCLT", "Charlotte Douglas International",  "Charlotte",  GeoPosition.fromDegreesAndFeet(35.2144, -80.9473, 747.9), true)
  val KBOS = new Airport("KBOS", "Boston Logan International", "Boston", GeoPosition.fromDegreesAndFeet(42.3656, -71.0096, 19.1), true)
  val KLGA = new Airport("KLGA", "LaGuardia", "New York", GeoPosition.fromDegreesAndFeet(40.7769, -73.8740, 20.6), true)
  val KCVG = new Airport("KCVG", "Cincinnati-Northern Kentucky International", "Cincinnati", GeoPosition.fromDegreesAndFeet(39.0533, -84.6630, 896.2), false)  // not yet ASDE-X
  val KBWI = new Airport("KBWI", "Baltimore-Washington International", "Baltimore", GeoPosition.fromDegreesAndFeet(39.1774, -76.6684, 143.2), true)
  val PHNL = new Airport("PHNL", "Honolulu International", "Honolulu", GeoPosition.fromDegreesAndFeet(21.3178275, -157.9202631, 12.9), true)
  val KPIT = new Airport("KPIT", "Pittsburgh International", "Pittsburgh", GeoPosition.fromDegreesAndFeet(40.4958, -80.2413, 1202.9), false) // not yet ASDE-X
  val KSLC = new Airport("KSLC", "Salt Lake City International", "Salt Lake City", GeoPosition.fromDegreesAndFeet(40.788333, -111.977778, 4227.4), true)
  val KFLL = new Airport("KFLL", "Ft Lauderdale Hollywood International", "Ft Lauderdale", GeoPosition.fromDegreesAndFeet(26.0742, -80.1506, 64.9), true)
  val KIAD = new Airport("KIAD", "Washington Dulles International", "Washington DC", GeoPosition.fromDegreesAndFeet(38.944444, -77.455833, 313.0), true)
  val KMDW = new Airport("KMDW", "Chicago Midway", "Chicago", GeoPosition.fromDegreesAndFeet(41.7868, -87.7522, 619.8), true)
  val KTPA = new Airport("KTPA", "Tampa International", "Tampa", GeoPosition.fromDegreesAndFeet(27.9835, -82.5371, 26.4), false)  // not yet ASDE-X
  val KSAN = new Airport("KSAN", "San Diego International Lindburgh Field", "San Diego", GeoPosition.fromDegreesAndFeet(32.7338, -117.1933, 17), true)
  val KPDX = new Airport("KPDX", "Portland International", "Portland", GeoPosition.fromDegreesAndFeet(45.5898, -122.5951, 30.8), false) // not yet ASDE-X
  val KDCA = new Airport("KDCA", "Washington Reagan International", "Washington D.C.", GeoPosition.fromDegreesAndFeet(38.8512, -77.0402, 14.1), true)
  val KCLE = new Airport("KCLE", "Cleveland Hopkins International", "Cleveland", GeoPosition.fromDegreesAndFeet(41.4124, -81.8480, 799.4), false) // not yet ASDE-X
  val KMEM = new Airport("KMEM", "Memphis International Airport", "Memphis", GeoPosition.fromDegreesAndFeet(35.0421, -89.9792, 340.9), true)

  // ..and more to follow

  val airportList = Seq(
    KATL,KBOS,KBWI,KCLE,KCLT,KCVG,KDCA,KDEN,KDFW,KDTW,
    KEWR,KFLL,KIAD,KIAH,KJFK,KLAS,KLAX,KLGA,
    KMCO,KMDW,KMEM,KMIA,KMSP,KORD,KPDX,KPHL,KPHX,KPIT,
    KSAN,KSEA,KSFO,KSLC,KSTL,KTPA,
    PHNL).sortWith( _.city < _.city)
  val asdexAirports = airportList.foldLeft(SortedMap.empty[String,Airport]) { (m, a) => m + (a.id -> a) }
  val allAirports = asdexAirports
  val airportNames = allAirports.keySet.toSeq

  // can be used for airport selection lists, to reset the selection


  final val NoAirport = new Airport(NoneId,"no airport","",GeoPosition.fromDegreesAndFeet(0,0,0),false){
    override def isMatching (s: String): Boolean = false
    override def isMatching (r: Regex): Boolean = false
    override def matchesNone: Boolean = true
  }

  final val AnyAirport = new Airport(AllId,"any airport","",GeoPosition.fromDegreesAndFeet(0,0,0),false){
    override def isMatching (s: String): Boolean = true
    override def isMatching (r: Regex): Boolean = true
    override def matchesAny: Boolean = true
  }

  def get(s: String): Option[Airport] = {
    allAirports.get(s).orElse {
      if (s == NoneId) Some(NoAirport)
      else if (s == AllId) Some(AnyAirport)
      else None
    }
  }

  def getId (s: String): Option[String] = get(s).map(_.id)
}

/**
  * represents and locates airports
  */
class Airport (val id: String,
               val name: String,
               val city: String,
               val position: GeoPosition,
               val hasAsdex: Boolean) extends GeoPositioned {
  def elevation: Length = position.altitude

  def isMatching (s: String): Boolean = s.equals(id)
  def isMatching (r: Regex): Boolean = r.matches(id)

  def matchesAny: Boolean = false
  def matchesNone: Boolean = false

  override def equals(other: Any): Boolean = {
    other match {
      case o: Airport => id == o.id
      case _ => false
    }
  }

  override def toString(): String = id
}

// matchable Seq type for Airports
trait Airports extends Seq[Airport]


//--- serialization

class AirportSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[Airport](system) {
  override def serialize(t: Airport): Unit = {
    writeUTF(t.id)
  }

  override def deserialize(): Airport = {
    val id = readUTF()
    Airport.get(id) match {
      case Some(airport) => airport
      case None => throw new RuntimeException(s"unknown airport: $id")
    }
  }
}