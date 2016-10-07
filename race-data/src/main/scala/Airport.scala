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

package gov.nasa.race.data

import squants.Length
import squants.space.Feet

import scala.collection.SortedMap

/**
  * list of airports, with respective ASDE-X support as of 09/16
  *
  * TODO - verify against http://www.airnav.com/airport/<ID>
  *
  * TODO - since this is dynamic data we might want to turn this into a config file that can be updated
  * without recompilation
  */
object Airport {
  val KATL = new Airport("KATL", "Hartsfield - Jackson Atlanta International", "Atlanta", LatLonPos.fromDegrees(33.636667, -84.428056), Feet(1027), true)
  val KORD = new Airport("KORD", "Chicago O'Hare International", "Chicago", LatLonPos.fromDegrees(41.978611, -87.904722), Feet(668), true)
  val KLAX = new Airport("KLAX", "Los Angeles International",  "Los Angeles",  LatLonPos.fromDegrees(33.9425, -118.408056), Feet(125), true)
  val KDFW = new Airport("KDFW", "Dallas/Fort Worth International", "Dallas/Fort Worth", LatLonPos.fromDegrees(32.896944, -97.038056), Feet(607), true)
  val KPHX = new Airport("KPHX", "Phoenix Sky Harbor International", "Phoenix", LatLonPos.fromDegrees(33.4373, -112.0078), Feet(1134.6), true)
  val KDEN = new Airport("KDEN", "Denver International", "Denver", LatLonPos.fromDegrees(39.861667, -104.673056), Feet(5433.8), true)
  val KLAS = new Airport("KLAS", "Mc Carran International", "Las Vegas",  LatLonPos.fromDegrees(36.08, -115.152222), Feet(2181), true)
  val KIAH = new Airport("KIAH", "George Bush International", "Houston", LatLonPos.fromDegrees(29.9902, -95.3368), Feet(96.4), true)
  val KMSP = new Airport("KMSP", "Minneapolis-St Paul International", "Minneapolis", LatLonPos.fromDegrees(44.8848, -93.2223), Feet(841.8), true)
  val KDTW = new Airport("KDTW", "Detroit Metropolitan Wayne International", "Detroit", LatLonPos.fromDegrees(42.2162, -83.3554), Feet(645.2), true)
  val KJFK = new Airport("KJFK", "John F Kennedy International", "New York", LatLonPos.fromDegrees(40.639722, -73.778889), Feet(12.7), true)
  val KSFO = new Airport("KSFO", "San Francisco International", "San Francisco",  LatLonPos.fromDegrees(37.618889, -122.375), Feet(13), true)
  val KEWR = new Airport("KEWR", "Newark International", "Newark", LatLonPos.fromDegrees(40.6895, -74.1745), Feet(17.4), true)
  val KMIA = new Airport("KMIA", "Miami International", "Miami", LatLonPos.fromDegrees(25.7959, -80.2870), Feet(8.5), true)
  val KMCO = new Airport("KMCO", "Orlando International", "Orlando", LatLonPos.fromDegrees(28.4312, -81.3081), Feet(96.4), true)
  val KSEA = new Airport("KSEA", "Seattle-Tacoma International", "Seattle", LatLonPos.fromDegrees(47.4502, -122.3088), Feet(432.5), true)
  val KSTL = new Airport("KSTL", "Lambert-St Louis International", "St Louis", LatLonPos.fromDegrees(38.7431, -90.3651), Feet(618.0), true)
  val KPHL = new Airport("KPHL", "Philadelphia International", "Philadelphia", LatLonPos.fromDegrees(39.8744, -75.2424), Feet(36.1), true)
  val KCLT = new Airport("KCLT", "Charlotte Douglas International",  "Charlotte",  LatLonPos.fromDegrees(35.2144, -80.9473), Feet(747.9), true)
  val KBOS = new Airport("KBOS", "Boston Logan International", "Boston", LatLonPos.fromDegrees(42.3656, -71.0096), Feet(19.1), true)
  val KLGA = new Airport("KLGA", "LaGuardia", "New York", LatLonPos.fromDegrees(40.7769, -73.8740), Feet(20.6), true)
  val KCVG = new Airport("KCVG", "Cincinnati-Northern Kentucky International", "Cincinnati", LatLonPos.fromDegrees(39.0533, -84.6630), Feet(896.2), false)  // not yet ASDE-X
  val KBWI = new Airport("KBWI", "Baltimore-Washington International", "Baltimore", LatLonPos.fromDegrees(39.1774, -76.6684), Feet(143.2), true)
  val PHNL = new Airport("PHNL", "Honolulu International", "Honolulu", LatLonPos.fromDegrees(21.3178275, -157.9202631), Feet(12.9), true)
  val KPIT = new Airport("KPIT", "Pittsburgh International", "Pittsburgh", LatLonPos.fromDegrees(40.4958, -80.2413), Feet(1202.9), false) // not yet ASDE-X
  val KSLC = new Airport("KSLC", "Salt Lake City International", "Salt Lake City", LatLonPos.fromDegrees(40.788333, -111.977778), Feet(4227.4), true)
  val KFLL = new Airport("KFLL", "Ft Lauderdale Hollywood International", "Ft Lauderdale", LatLonPos.fromDegrees(26.0742, -80.1506), Feet(64.9), true)
  val KIAD = new Airport("KIAD", "Washington Dulles International", "Washington DC", LatLonPos.fromDegrees(38.944444, -77.455833), Feet(313.0), true)
  val KMDW = new Airport("KMDW", "Chicago Midway", "Chicago", LatLonPos.fromDegrees(41.7868, -87.7522), Feet(619.8), true)
  val KTPA = new Airport("KTPA", "Tampa International", "Tampa", LatLonPos.fromDegrees(27.9835, -82.5371), Feet(26.4), false)  // not yet ASDE-X
  val KSAN = new Airport("KSAN", "San Diego International Lindburgh Field", "San Diego", LatLonPos.fromDegrees(32.7338, -117.1933), Feet(17), true)
  val KPDX = new Airport("KPDX", "Portland International", "Portland", LatLonPos.fromDegrees(45.5898, -122.5951), Feet(30.8), false) // not yet ASDE-X
  val KDCA = new Airport("KDCA", "Washington Reagan International", "Washington D.C.", LatLonPos.fromDegrees(38.8512, -77.0402), Feet(14.1), true)
  val KCLE = new Airport("KCLE", "Cleveland Hopkins International", "Cleveland", LatLonPos.fromDegrees(41.4124, -81.8480), Feet(799.4), false) // not yet ASDE-X
  val KMEM = new Airport("KMEM", "Memphis International Airport", "Memphis", LatLonPos.fromDegrees(35.0421, -89.9792), Feet(340.9), true)

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
}

/**
  * represents and locates airports
  */
case class Airport (id: String, name: String, city: String, pos: LatLonPos, elev: Length, hasAsdex: Boolean)
