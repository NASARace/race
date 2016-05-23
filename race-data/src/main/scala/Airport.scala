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

import scala.collection.SortedMap


object Airport {
  val KLAX = new Airport("KLAX", "Los Angeles International",  "Los Angeles",  LatLonPos.fromDegrees(33.9425, -118.408056), true)       // LA
  val KSLC = new Airport("KSLC", "Salt Lake City International", "Salt Lake City", LatLonPos.fromDegrees(40.788333, -111.977778), true)     // Salt Lake
  val KLAS = new Airport("KLAS", "Mc Carran International", "Las Vegas",  LatLonPos.fromDegrees(36.08, -115.152222), true)         // Las Vegas
  val KDEN = new Airport("KDEN", "Denver International", "Denver", LatLonPos.fromDegrees(39.861667, -104.673056), true)     // Denver
  val KDFW = new Airport("KDFW", "Dallas/Fort Worth International", "Dallas/Fort Worth", LatLonPos.fromDegrees(32.896944, -97.038056), true)      // Dallas
  val KIAD = new Airport("KIAD", "Washington Dulles International", "Washington DC", LatLonPos.fromDegrees(38.944444, -77.455833), true)      // DC
  val KORD = new Airport("KORD", "Chicago O'Hare International", "Chicago", LatLonPos.fromDegrees(41.978611, -87.904722), true)      // Chicago
  val KJFK = new Airport("KJFK", "John F Kennedy International", "New York", LatLonPos.fromDegrees(40.639722, -73.778889), true)      // New York JFK
  val KSFO = new Airport("KSFO", "San Francisco International", "San Francisco",  LatLonPos.fromDegrees(37.618889, -122.375), true)        // San Francisco
  val KATL = new Airport("KATL", "Hartsfield - Jackson Atlanta International", "Atlanta", LatLonPos.fromDegrees(33.636667, -84.428056), true)      // Atlanta

  // and many more to follow: KMSP,KSAN,KMDW,KBDL,KSEA,KEWR,PHNL,KCLT,KIAH,KMEM,KPHX,KMCO,KBWI,KDTW,KHOU,KSNA,KFLL,KBWI,KSDF,KPHL,..

  val airportList = Seq(KATL,KDEN,KDFW,KIAD,KJFK,KLAS,KLAX,KORD,KSFO,KSLC)
  val asdexAirports = airportList.foldLeft(SortedMap.empty[String,Airport]) { (m, a) => m + (a.id -> a) }
  val allAirports = asdexAirports
  val airportNames = allAirports.keySet.toSeq
}

/**
  * represents and locates airports
  */
case class Airport (id: String, name: String, city: String, pos: LatLonPos, hasAsdex: Boolean)
