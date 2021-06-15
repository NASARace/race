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

import akka.actor.ExtendedActorSystem
import gov.nasa.race.common.{AllId, NoneId}
import gov.nasa.race.core.SingleTypeAkkaSerializer
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}

import scala.collection.immutable.SortedMap
import scala.util.matching.Regex

/**
  * US Air Route Traffic Control Centers
  */
object ARTCC {
  def apply (id: String, name: String, state: String, area: String, lat: Double, lon: Double) = {
    new ARTCC(id,name,state,area,GeoPosition.fromDegrees(lat,lon))
  }

  val ZMA = new ARTCC("ZMA",	"Miami",            "FL",   "Eastern",  	GeoPosition.fromDegrees(25.82485,  -80.31933))
  val ZJX = new ARTCC("ZJX",	"Jacksonville",	    "FL",   "Eastern",  	GeoPosition.fromDegrees(30.69892,  -81.90829))
  val ZDC = new ARTCC("ZDC",	"Washington DC",	  "DC",   "Eastern",  	GeoPosition.fromDegrees(39.10153,  -77.54286))
  val ZNY = new ARTCC("ZNY",	"New York",	        "NY",   "Eastern",  	GeoPosition.fromDegrees(40.784306, -73.096861))
  val ZBW = new ARTCC("ZBW",	"Boston",	          "NH",   "Eastern",  	GeoPosition.fromDegrees(42.73517,  -71.48056))
  val ZOB = new ARTCC("ZOB",	"Cleveland",	      "OH",   "Central",  	GeoPosition.fromDegrees(41.29725,  -82.20638))
  val ZID = new ARTCC("ZID",	"Indianapolis",	    "IN",   "Central",  	GeoPosition.fromDegrees(39.73813,  -86.28036))
  val ZTL = new ARTCC("ZTL",	"Atlanta",	        "GA",   "Eastern",  	GeoPosition.fromDegrees(33.379651, -84.29679))
  val ZHU = new ARTCC("ZHU",	"Houston",	        "TX",   "Central",  	GeoPosition.fromDegrees(29.961437, -95.33194))
  val ZME = new ARTCC("ZME",	"Memphis",	        "TN",   "Eastern",  	GeoPosition.fromDegrees(35.06730,  -89.95550))
  val ZKC = new ARTCC("ZKC",	"Kansas City",	    "KS",   "Central",  	GeoPosition.fromDegrees(38.88005,  -94.79063))
  val ZAU = new ARTCC("ZAU",	"Chicago",	        "IL",   "Central",  	GeoPosition.fromDegrees(41.78276,  -88.33132))
  val ZMP = new ARTCC("ZMP",	"Minneapolis",	    "MN",   "Central",  	GeoPosition.fromDegrees(44.63729,  -93.15214))
  val ZFW = new ARTCC("ZFW",	"Fort Worth",	      "TX",   "Central",  	GeoPosition.fromDegrees(32.83078,  -97.06665))
  val ZAB = new ARTCC("ZAB",	"Albuquerque",	    "NM",   "Central",  	GeoPosition.fromDegrees(35.17341, -106.56742))
  val ZDV = new ARTCC("ZDV",	"Denver",	          "CO",   "Western",  	GeoPosition.fromDegrees(40.18731, -105.12707))
  val ZLC = new ARTCC("ZLC",	"Salt Lake City",	  "UT",   "Western",  	GeoPosition.fromDegrees(40.78607, -111.95232))
  val ZLA = new ARTCC("ZLA",	"Los Angeles",	    "CA",   "Western",  	GeoPosition.fromDegrees(34.60331, -118.08379))
  val ZOA = new ARTCC("ZOA",	"Oakland",	        "CA",   "Western",  	GeoPosition.fromDegrees(37.5429,  -122.01601))
  val ZSE = new ARTCC("ZSE",	"Seattle",	        "WA",   "Western",  	GeoPosition.fromDegrees(47.28693, -122.18819))
  val ZAN = new ARTCC("ZAN",	"Anchorage",	      "AK",   "Western",  	GeoPosition.fromDegrees(61.22920, -149.78030))
  val ZHN = new ARTCC("ZHN",	"Honolulu",	        "HI",   "Western",  	GeoPosition.fromDegrees(21.32087, -157.92654))
  val ZSU = new ARTCC("ZSU",	"San Juan",         "PR",   "Eastern",  	GeoPosition.fromDegrees(18.431273, -65.993459))
  val ZUA = new ARTCC("ZUA",	"Guam",             "GU",   "Western",  	GeoPosition.fromDegrees(13.47885, -144.79708))

  val artccList = Seq(
    ZMA, ZJX, ZDC, ZNY, ZBW, ZOB, ZID, ZTL, ZHU, ZME, ZKC, ZAU,
    ZMP, ZFW, ZAB, ZDV, ZLC, ZLA, ZOA, ZSE, ZAN, ZHN, ZSU, ZUA
  ).sortWith( _.id < _.id )

  val artccs = artccList.foldLeft(SortedMap.empty[String,ARTCC]) { (m, a) => m + (a.id -> a) }

  //--- pseudo ARTCCs to allow more efficient filtering

  final val NoARTCC = new ARTCC(NoneId,"","","", GeoPosition.undefinedPos) {
    override def isMatching (s: String): Boolean = false
    override def isMatching (r: Regex): Boolean = false
    override def matchesNone: Boolean = true
  }
  final val AnyARTCC = new ARTCC(AllId,"","","", GeoPosition.undefinedPos){
    override def isMatching (s: String): Boolean = true
    override def isMatching (r: Regex): Boolean = true
    override def matchesAny: Boolean = true
  }

  def get(s: String): Option[ARTCC] = {
    artccs.get(s).orElse {
      if (s == NoneId) Some(NoARTCC)
      else if (s == AllId) Some(AnyARTCC)
      else None
    }
  }

  def getId (s: String): Option[String] = get(s).map(_.id)
}

case class ARTCC (id: String, name: String, state: String, area: String, position: GeoPosition) extends GeoPositioned {
  def isMatching (s: String): Boolean = id == s
  def isMatching (r: Regex): Boolean = r.matches(id)

  // ids are unique
  override def equals(other: Any): Boolean = {
    other match {
      case o: ARTCC => id == o.id
      case _ => false
    }
  }

  def matchesAny: Boolean = false
  def matchesNone: Boolean = false

  override def toString(): String = id
}

// if we need to match on a Seq type
trait ARTCCs extends Seq[ARTCC]

//--- serialization

class ARTCCSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[ARTCC](system) {
  override def serialize(t: ARTCC): Unit = {
    writeUTF(t.id)
  }

  override def deserialize(): ARTCC = {
    val id = readUTF()
    ARTCC.get(id) match {
      case Some(artcc) => artcc
      case None => throw new RuntimeException(s"unknown ARTCC: $id")
    }
  }
}