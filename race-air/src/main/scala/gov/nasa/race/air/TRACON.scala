/*
 * Copyright (c) 2017, United States Government, as represented by the
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
import gov.nasa.race.common.{AllId, ContactInfo, NoneId}
import gov.nasa.race.core.SingleTypeAkkaSerializer
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}

import scala.collection.immutable.SortedMap
import scala.util.matching.Regex

object TRACON {
  def apply (id: String, name: String, state: String, area: String, lat: Double, lon: Double) = {
    new TRACON(id,name, state, area, GeoPosition.fromDegrees(lat,lon))
  }

  val A11 = TRACON( "A11",  	"Anchorage", 	          "AK",   "Western",   61.1774811, -149.9818601)
  val A80 = TRACON( "A80",  	"Atlanta", 	            "GA",   "Eastern",   33.3517888,-84.5525863)
  val A90 = TRACON( "A90",  	"Boston",               "NH",   "Eastern",   42.816896,-71.4954427)
  val C90 = TRACON( "C90",  	"Chicago", 	            "IL",   "Central",   42.010397,-88.3068217)
  val D01 = TRACON( "D01",  	"Denver", 	            "CO",   "Western",   39.8223354,-104.6783454)
  val D10 = TRACON( "D10",  	"Dallas - Ft Worth",	  "TX",   "Central",   32.8993755,-97.0422335)
  val D21 = TRACON( "D21",  	"Detroit",	            "MI",   "Central",   42.2161722,-83.3575729)
  val F11 = TRACON( "F11",  	"Central Florida",	    "FL",   "Eastern",   28.4308421,-81.3072033)
  val I90 = TRACON( "I90",  	"Houston",	            "TX",   "Central",   29.9547551,-95.3233041)
  val L30 = TRACON( "L30",  	"Las Vegas",	          "NV",   "Western",   36.0833965,-115.1488413)
  val M03 = TRACON( "M03",  	"Memphis",	            "TN",   "Central",   35.0499022,-89.9836507)
  val M98 = TRACON( "M98",  	"Minneapolis",	        "MN",   "Central",   44.8880923,-93.2242513)
  val N90 = TRACON( "N90",  	"New York",	            "NY",   "Eastern",   40.7378277,-73.5886574)
  val NCT = TRACON( "NCT",  	"Northern California", 	"CA",   "Western",   38.5595005,-121.2606418)
  val P32 = TRACON( "P31",  	"Pensacola", 	          "FL",   "Eastern",   30.4686811,-87.1927309)
  val P50 = TRACON( "P50",  	"Phoenix",	            "AZ",   "Western",   33.4354256,-112.0076295)
  val P80 = TRACON( "P80",  	"Portland",	            "OR",   "Western",   45.5872736,-122.5902547)
  val PCT = TRACON( "PCT",  	"Potomac",	            "VA",   "Eastern",   38.7474922,-77.6727617)
  val R90 = TRACON( "R90",  	"Omaha",	              "NE",   "Central",   41.1430603,-95.9060088)
  val S46 = TRACON( "S46",  	"Seattle",              "WA",   "Western",   47.4572303,-122.325166)
  val S56 = TRACON( "S56",  	"Salt Lake City",	      "UT",   "Western",   40.7961385,-111.9881199)
  val SCT = TRACON( "SCT",  	"Southern California",	"CA",   "Western",   32.890137,-117.1186627)
  val T75 = TRACON( "T75",  	"St Louis",	            "MO",   "Central",   38.707042,-90.6798347)
  val U90 = TRACON( "U90",  	"Tucson",	              "AZ",   "Western",   32.1666465,-110.8746705)
  val Y90 = TRACON( "Y90",  	"Yankee",	              "CT",   "Eastern",   41.9456049,-72.6909371)

  val IND = TRACON( "IND",    "Indianapolis Tower",   "IN",   "Central",   39.7074294,-86.3057181)
  val EVV = TRACON( "EVV",    "Evansville Tower",     "IN",   "Central",   38.04467,-87.5323547)
  val FWA = TRACON( "FWA",    "Fort Wayne Tower",     "IN",    "Central",  40.9722456,-85.1899334)
  val HUF = TRACON( "HUF",    "Terre Haute/Hulman ATCT", "IN", "Central",  39.4612872,-87.30629)
  val SBN = TRACON( "SBN",    "South Bend Tower",      "IN",   "Central",  41.708278,-86.3162125)

  val CMI = TRACON( "CMI",    "Champaign Tower",       "IL",   "Central",  40.0393262,-88.257327)
  val MLI = TRACON( "MLI",    "Quad City Tower",       "IL",   "Central",  41.4155117,-90.6198191)
  val PIA = TRACON( "PIA",    "Peoria Tower",          "IL",   "Central",  40.6667516,-89.6938988)
  val SPI = TRACON( "SPI",    "Springfield Tower",     "IL",   "Central",  39.8444866,-89.6878402)

  val CMH = TRACON( "CMH",    "Columbus Tower",         "OH",  "Central",  39.9980859,-82.8952307)

  val CVG = TRACON( "CVG",    "Cincinnati Tower",       "KY",  "Central",  39.0404759,-84.6610529)

  val SBA = TRACON( "SBA",    "Santa Barbara Tower",  "CA",   "Western",   34.4309345,-119.8474713)
  val JCF = TRACON( "JCF",    "Joshua Control",       "CA",   "Western",   34.9192786,-117.9044986)
  val FAT = TRACON( "FAT",    "Fresno Tower",         "CA",   "Western",   36.773319,-119.7240687)
  val BFL = TRACON( "BFL",    "Bakersfield Tower",    "CA",   "Western",   35.4341064,-119.0482688)


  //... and many more to follow ...


  val traconList = Seq(
    A11,A80, A90, C90, D01, D10, D21, F11, I90, L30, M03, M98, N90,
    NCT, P32, P50, P80, PCT, R90, S46, S56, SCT, T75, U90, Y90,

    IND,EVV,FWA,HUF,SBN,
    CMI,MLI,PIA,SPI,
    CMH,
    CVG,
    SBA,JCF,FAT,BFL
  ).sortWith( _.id < _.id )

  val tracons = traconList.foldLeft(SortedMap.empty[String,TRACON]) { (m, a) => m + (a.id -> a) }

  //--- pseudo TRACONs

  // can be used for selection lists to reset selection
  final val NoTracon = new TRACON(NoneId, "", "", "", GeoPosition.undefinedPos) {
    override def isMatching (s: String): Boolean = false
    override def isMatching (r: Regex): Boolean = false
    override def matchesNone: Boolean = true
  }

  final val AnyTracon = new TRACON(AllId, "", "", "", GeoPosition.undefinedPos) {
    override def isMatching (s: String): Boolean = true
    override def isMatching (r: Regex): Boolean = true
    override def matchesAny: Boolean = true
  }

  def get(s: String): Option[TRACON] = {
    tracons.get(s).orElse {
      if (s == NoneId) Some(NoTracon)
      else if (s == AllId) Some(AnyTracon)
      else None
    }
  }

  def getId (s: String): Option[String] = get(s).map(_.id)
}

/**
  * represents TRACONs (Terminal Radar Approach Control facilities)
  */
class TRACON(val id: String, val name: String, val state: String, val area: String, val position: GeoPosition) extends GeoPositioned {
  def toShortString = s"$id - $name"

  def isMatching (s: String): Boolean = s.equals(id)
  def isMatching (r: Regex): Boolean = r.matches(id)

  def matchesAny: Boolean = false
  def matchesNone: Boolean = false

  override def equals(other: Any): Boolean = {
    other match {
      case o: TRACON => id == o.id
      case _ => false
    }
  }

  override def toString(): String = id
}

// matchable Seq type for TRACONs
trait TRACONs extends Seq[TRACON]

//--- serialization support
class TRACONSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[TRACON](system) {
  override def serialize(t: TRACON): Unit = {
    writeUTF(t.id)
  }

  override def deserialize(): TRACON = {
    val id = readUTF()
    TRACON.get(id) match {
      case Some(tracon) => tracon
      case None => throw new RuntimeException(s"unknown TRACON: $id")
    }
  }
}