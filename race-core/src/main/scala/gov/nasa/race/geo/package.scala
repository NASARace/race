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

import gov.nasa.race.common._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._


/**
  * package gov.nasa.race.geo contains support code for geospatial applications, including respective
  * interfacing to the `squants` library (units of measure)
  */
package object geo {

  final val MeanEarthRadius = Kilometers(6371)  // IERS mean radius of semi axes
  final val NM = Meters(1852.0) // length of Nautical Mile in meters

  //--- WGS84 earth axes constants
  final val RE_E = 6378137.0000e+0  // semi-major (equatorial) earth radius in [m]
  final val RE_E2 = RE_E * RE_E
  final val RE_N = 6356752.3141e+0  // semi-minor (polar) earth radius in [m]
  final val RE_N2 = RE_N * RE_N

  final val RE_FLATTENING = ( RE_E - RE_N ) / RE_E
  final val INV_RE_FLATTENING = 298.257223563
  final val E_ECC = 2.0 * RE_FLATTENING - (RE_FLATTENING * RE_FLATTENING)
  final val E2 = (RE_E*RE_E - RE_N*RE_N)/(RE_E*RE_E) // squared eccentricity: 0.0066943799901413165

  // geometric names for ellipsoids
  final val A = RE_E
  final val A2 = RE_E2
  final val B = RE_N
  final val AB2 = RE_E2 * RE_N2

  final val MRC_FACTOR = (1.0 - E2) / A2

  final val NM_DEG = NM * 60.0
  def angularDistance (l: Length) = Degrees(l / NM_DEG)

  //--- various abstractions of position objects

  // we use a function interface here to allow concrete types to use their own storage formats

  case class XYPos (x: Length, y: Length)

  //--- WGS84 based elliptic functions

  def meridionalCurvatureRadius (lat: Angle): Double = AB2 / Math.pow( squared(A*Cos(lat)) + squared(B*Sin(lat)), 1.5)

  def parallelCurvatureRadius (lat: Angle): Double = A2 / Math.sqrt(squared(A*Cos(lat)) + squared(B*Sin(lat)))
  def parallelRadius (lat: Angle): Double = parallelCurvatureRadius(lat) * Cos(lat)

  def metersPerParallelDeg(lat: Angle): Length = Meters(parallelRadius(lat) * Angle.DegreesInRadian)
  def metersPerMeridianDeg(lat: Angle): Length = Meters(meridionalCurvatureRadius(lat) * Angle.DegreesInRadian)

  def degPerParallelMeter(lat: Angle): Angle = Radians( 1.0 / parallelRadius(lat))
  def degPerParallelLength(lat: Angle, l: Length): Angle = Radians( l.toMeters / parallelRadius(lat))

  def degPerMeridianMeter(lat: Angle): Angle = Radians( 1.0 / meridionalCurvatureRadius(lat))
  def degPerMeridianLength(lat: Angle, l: Length): Angle = Radians( l.toMeters / meridionalCurvatureRadius(lat))

  def primeVerticalRadiusOfCurvature (lat: Angle): Length = Meters(A / Math.sqrt(1.0 - E2 * Sin2(lat)))
  def meridionalRadiusOfCurvature (lat: Angle): Length = Meters(MRC_FACTOR * cubed(primeVerticalRadiusOfCurvature(lat).toMeters))

  def averageRadiusOfCurvature (lat: Angle): Length = {
    val prc = primeVerticalRadiusOfCurvature(lat).toMeters
    val mrc = MRC_FACTOR * cubed(prc)
    Meters(Math.sqrt(prc * mrc))
  }
  def meanRadiusOfCurvature (lat: Angle): Length = {
    val prc = primeVerticalRadiusOfCurvature(lat).toMeters
    val mrc = MRC_FACTOR * cubed(prc)
    Meters(2.0 / (1.0/prc + 1.0/mrc))
  }


  final val CONTIG_US_CENTER_LAT = Degrees(39.8333333)
  final val CONTIG_US_CENTER_LON = Degrees(-98.583333)

  // -> 6 digit lat/lon is accurate to ~11cm,~8cm at mean US lat
}
