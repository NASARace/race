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

/**
  * Air Route Traffic Control Centers
  */
object ARTCC {
  def apply (id: String, name: String, lat: Double, lon: Double) = new ARTCC(id,name,GeoPosition.fromDegrees(lat,lon))
  /*
  val zab = ARTCC("ZAB", "Albuquerque",     35.1730822,-106.5677151)
  val zab = ARTCC("ZAP", "Anchorage",       34.6047709,-118.0893658)
  val zab = ARTCC("ZAU", "Chicago",         41.7822561,-88.3314505)
  val zab = ARTCC("ZBW", "Boston",          lat, lon)
  val zab = ARTCC("ZDC", "Washington",      lat, lon)
  val zab = ARTCC("ZDV", "Denver",          lat, lon)
  val zab = ARTCC("ZFW", "Fort Worth",      lat, lon)
  val zab = ARTCC("ZHU", "Houston",         lat, lon)
  val zab = ARTCC("ZID", "Indianapolis",    lat, lon)
  val zab = ARTCC("ZJX", "Jacksonville",    lat, lon)
  val zab = ARTCC("ZKC", "Kansas City",     lat, lon)
  val zab = ARTCC("ZLA", "Los Angeles",     lat, lon)
  val zab = ARTCC("ZLC", "Salt Lake City",  lat, lon)
  val zab = ARTCC("ZMA", "Miami",           lat, lon)
  val zab = ARTCC("ZME", "Memphis",         lat, lon)
  val zab = ARTCC("ZMP", "Minneapolis",     lat, lon)
  val zab = ARTCC("ZNY", "New York",        lat, lon)
  val zab = ARTCC("ZOA", "Oakland",         lat, lon)
  val zab = ARTCC("ZOB", "Cleveland",       lat, lon)
  val zab = ARTCC("ZSE", "Seattle",         lat, lon)
  val zab = ARTCC("ZTL", "Atlanta",         lat, lon)
  val zab = ARTCC("ONY", "ODAPS New York",  lat, lon)
  val zab = ARTCC("OOA", "ODAPS Oakland",   lat, lon)
  */

}


case class ARTCC (id: String, name: String, position: GeoPosition) extends GeoPositioned