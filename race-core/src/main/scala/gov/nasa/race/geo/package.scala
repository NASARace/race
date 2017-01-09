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

package gov.nasa.race

import org.joda.time.DateTime
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._

/**
  * package gov.nasa.race.geo contains support code for geospatial applications, including respective
  * interfacing to the `squants` library (units of measure)
  */
package object geo {

  final val MeanEarthRadius = Kilometers(6371)
  final val NM = 1852.0 // length of Nautical Mile in meters

  //--- WGS84 earth axes constants
  final val RE_E = 6378137.0000e+0
  final val RE_N = 6356752.3141e+0
  final val RE_FLATTENING = ( RE_E - RE_N ) / RE_E
  final val INV_RE_FLATTENING = 298.257223563
  final val E_ECC = 2.0 * RE_FLATTENING - RE_FLATTENING * RE_FLATTENING

  //--- various abstractions of position objects

  trait Positionable {
    def position: LatLonPos
  }
  trait AltitudePositionable extends Positionable {
    def altitude: Length
  }
  trait DatedAltitudePositionable extends AltitudePositionable {
    def date: DateTime
  }
  trait MovingPositionable extends AltitudePositionable {
    def heading: Angle
    def speed: Speed
  }

}
