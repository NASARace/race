/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.gis

import gov.nasa.race.geo.{Datum, GeoPosition, XyzPos}
import gov.nasa.race.uom.Length

/**
  * a type that can be kept in a GisItemDB
  */
trait GisItem {
  def name: String  // unique alphanumeric identifier for item
  def pos: GeoPosition

  def ecef: XyzPos = Datum.wgs84ToECEF(pos)
  def hash: Int = name.hashCode
  def ecefDistanceTo(xyz: XyzPos): Length = ecef.distanceTo(xyz)

  def addStrings (db: GisItemDBFactory[_]): Unit
}
