/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import gov.nasa.race.geo.{GeoPosition, GeoPositioned}

import scala.collection.immutable.SortedMap

/**
  * US Air Route Traffic Control Centers
  */
object ARTCC {
  def apply (id: String, name: String, state: String, area: String, lat: Double, lon: Double) = {
    new ARTCC(id,name,state,area,GeoPosition.fromDegrees(lat,lon))
  }

  val ZMA = ARTCC("ZMA",	"Miami",            "FL",   "Eastern",  	25.82485,  -80.31933)
  val ZJX = ARTCC("ZJX",	"Jacksonville",	    "FL",   "Eastern",  	30.69892,  -81.90829)
  val ZDC = ARTCC("ZDC",	"Washington DC",	  "DC",   "Eastern",  	39.10153,  -77.54286)
  val ZNY = ARTCC("ZNY",	"New York",	        "NY",   "Eastern",  	40.784306, -73.096861)
  val ZBW = ARTCC("ZBW",	"Boston",	          "NH",   "Eastern",  	42.73517,  -71.48056)
  val ZOB = ARTCC("ZOB",	"Cleveland",	      "OH",   "Central",  	41.29725,  -82.20638)
  val ZID = ARTCC("ZID",	"Indianapolis",	    "IN",   "Central",  	39.73813,  -86.28036)
  val ZTL = ARTCC("ZTL",	"Atlanta",	        "GA",   "Eastern",  	33.379651, -84.29679)
  val ZHU = ARTCC("ZHU",	"Houston",	        "TX",   "Central",  	29.961437, -95.33194)
  val ZME = ARTCC("ZME",	"Memphis",	        "TN",   "Eastern",  	35.06730,  -89.95550)
  val ZKC = ARTCC("ZKC",	"Kansas City",	    "KS",   "Central",  	38.88005,  -94.79063)
  val ZAU = ARTCC("ZAU",	"Chicago",	        "IL",   "Central",  	41.78276,  -88.33132)
  val ZMP = ARTCC("ZMP",	"Minneapolis",	    "MN",   "Central",  	44.63729,  -93.15214)
  val ZFW = ARTCC("ZFW",	"Fort Worth",	      "TX",   "Central",  	32.83078,  -97.06665)
  val ZAB = ARTCC("ZAB",	"Albuquerque",	    "NM",   "Central",  	35.17341, -106.56742)
  val ZDV = ARTCC("ZDV",	"Denver",	          "CO",   "Western",  	40.18731, -105.12707)
  val ZLC = ARTCC("ZLC",	"Salt Lake City",	  "UT",   "Western",  	40.78607, -111.95232)
  val ZLA = ARTCC("ZLA",	"Los Angeles",	    "CA",   "Western",  	34.60331, -118.08379)
  val ZOA = ARTCC("ZOA",	"Oakland",	        "CA",   "Western",  	37.5429,  -122.01601)
  val ZSE = ARTCC("ZSE",	"Seattle",	        "WA",   "Western",  	47.28693, -122.18819)
  val ZAN = ARTCC("ZAN",	"Anchorage",	      "AK",   "Western",  	61.22920, -149.78030)
  val ZHN = ARTCC("ZHN",	"Honolulu",	        "HI",   "Western",  	21.32087, -157.92654)
  val ZSU = ARTCC("ZSU",	"San Juan",         "PR",   "Eastern",  	18.431273, -65.993459)
  val ZUA = ARTCC("ZUA",	"Guam",             "GU",   "Western",  	13.47885, -144.79708)

  val artccList = Seq(
    ZMA, ZJX, ZDC, ZNY, ZBW, ZOB, ZID, ZTL, ZHU, ZME, ZKC, ZAU,
    ZMP, ZFW, ZAB, ZDV, ZLC, ZLA, ZOA, ZSE, ZAN, ZHN, ZSU, ZUA
  ).sortWith( _.id < _.id )

  val artccs = artccList.foldLeft(SortedMap.empty[String,ARTCC]) { (m, a) => m + (a.id -> a) }

  final val NoARTCC = new ARTCC("","","","", GeoPosition.undefinedPos)
}

case class ARTCC (id: String, name: String, state: String, area: String, position: GeoPosition) extends GeoPositioned