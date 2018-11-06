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

import gov.nasa.race.common.ContactInfo
import gov.nasa.race.geo.{GeoPositioned, GeoPosition}

import scala.collection.SortedMap

object Tracon {
  def apply (id: String, name: String, lat: Double, lon: Double,
             street: String, city: String, state: String, zip: String, phone: String) = {
    new Tracon(id,name, GeoPosition.fromDegrees(lat,lon), ContactInfo(street,city,state,zip,phone))
  }

  val A80 = Tracon("A80", "Atlanta", 33.3517888,-84.5525863,
                   "784 S. Hwy. 74", "Peachtree City", "GA", "30269",  "(678) 364-6000")
  val A90 = Tracon("A90", "Boston", 42.816896,-71.4954427,
                   "25 Robert Milligan Pkwy", "Merrimack", "NH", "03054",  "(603) 594-5501")
  val D01 = Tracon("D01", "Denver", 39.8216227,-104.6750039,
                   "26805 E 68th Ave", "Denver", "CO", "80249",  "(303) 342-1080")
  val IND = Tracon("IND", "Indianapolis Tower", 39.7018315,-86.3099555,
                   "4210 Midfield Service Rd", "Indianapolis", "IN", "46241", "(317) 227-5780")
  val L30 = Tracon("L30", "Las Vegas", 36.0828087,-115.1472187,
                   "699 Wright Brothers La.", "Las Vegas", "NV", "89119", "(702) 262-5900")
  val M03 = Tracon("M03", "Memphis", 35.0521946,-89.9824915,
                   "2515 Winchester Rd", "Memphis", "TN", "38116", "901-322-3350")
  val NCT = Tracon("NCT", "Northern California",  38.5607985,-121.2566981,
                   "11375 Douglas Rd", "Sacramento", "CA", "95655", "(916) 366-4280")
  val PCT = Tracon("PCT", "Potomac",  38.747488,-77.670573,
                   "3699 MacIntosh Dr", "Warrenton", "VA", "20187",  "(540) 349-7600")
  val SCT = Tracon("SCT", "Southern California",  32.89109,-117.11726,
                   "9175 Kearny Villa Rd", "San Diego", "CA", "92126", "(858) 537-5800")
  val Y90 = Tracon("Y90", "Yankee",  41.9456489,-72.6910157,
                   "35 Perimeter Rd", "Windsor Locks", "CT", "06096", "(860) 386-3500")
  //... and many more to follow ...


  val traconList = Seq(
    A80,A90,
    D01,
    IND,
    L30,
    M03,
    NCT,
    PCT,
    SCT,
    Y90
  ).sortWith( _.id < _.id )

  val tracons = traconList.foldLeft(SortedMap.empty[String,Tracon]) { (m, a) => m + (a.id -> a) }

  // can be used for selection lists to reset selection
  final val NoTracon = new Tracon("<none>", "", GeoPosition.fromDegrees(0,0),ContactInfo.NoContactInfo)
}

/**
  * represents TRACONs (Terminal Radar Approach Control facilities)
  */
case class Tracon (id: String, name: String, position: GeoPosition, contact: ContactInfo) extends GeoPositioned {
  def toShortString = s"$id - $name"
}
