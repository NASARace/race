/*
 * Copyright (c) 2023, United States Government, as represented by the
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
package gov.nasa.race.geo

import gov.nasa.race.common.atanh
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.Kilometers

object UtmPosition {

  // no 'I' or 'O' band
  val latBands = Array('A','B','C','D','E','F','G','H','J','K','L','M','N','P','Q','R','S','T','U','V','W','X')


  def utmZone (pos: GeoPosition): Int = {
    val lat = pos.latDeg
    val lon = pos.lonDeg

    // handle special cases (Svalbard/Norway)
    if (lat > 55 && lat < 64 && lon > 2 && lon < 6) {
      return 32
    }

    if (lat > 71) {
      if (lon >= 6 && lon < 9) {
        return 31
      }
      if ((lon >= 9 && lon < 12) || (lon >= 18 && lon < 21)) {
        return 33
      }
      if ((lon >= 21 && lon < 24) || (lon >= 30 && lon < 33)) {
        return 35
      }
    }

    if (lon >= -180 && lon <= 180) {
      (((lon + 180.0) / 6.0).toInt % 60) + 1
    } else if (lon > 180 && lon < 360) {
      ((lon / 6.0).toInt % 60) + 1

    } else {
      -1 // illegal input
    }
  }

  // Krueger approximation - see https://en.wikipedia.org/wiki/Universal_Transverse_Mercator_coordinate_system
  def from (pos: GeoPosition): Option[UtmPosition] = {
    import java.lang.Math._

    // val a = 6378.137
    // val f = 0.0033528106647474805 // 1.0/298.257223563
    // val n = 0.0016792203863837047 // f / (2.0 - f)
    // val n2 = 2.8197811060466384E-6 // n * n
    // val n3 = 4.7350339184131065E-9 // n2 * n
    // val n4 = 7.951165486017604E-12 // n2 * n2
    // val A = 6367.449145823416 // (a / (1.0 + n)) * (1 + n2/4.0 + n4/64.0)
    val α1 = 8.377318188192541E-4 // n/2.0 - (2.0/3.0)*n2 + (5.0/16.0)*n3
    val α2 = 7.608496958699166E-7 // (13.0/48.0)*n2 - (3.0/5.0)*n3
    val α3 = 1.2034877875966646E-9 // (61.0/240.0)*n3
    val C = 0.08181919084262149 // (2.0*sqrt(n)) / (1.0 + n)
    // val k0 = 0.9996
    val D = 6364.902166165087 // k0 * A
    val E0 = 500.0

    val latDeg = pos.latDeg
    val lonDeg = pos.lonDeg

    if (latDeg < -80.0 || latDeg > 84.0) return None
    val band = latBands( ((latDeg+80)/8).toInt)

    val φ = pos.lat.toRadians
    val λ = pos.lon.toRadians
    val utmZone = round((lonDeg + 180) / 6).toInt
    val λ0 = toRadians((utmZone-1)*6 - 180 + 3)
    val dλ = λ - λ0
    val N0 = if (φ < 0) 10000.0 else 0

    val sin_φ = sin(φ)
    val t = sinh( atanh(sin_φ) - C * atanh( C*sin_φ))

    val ξ = atan( t/cos(dλ))
    val `2ξ` = 2*ξ
    val `4ξ` = 4*ξ
    val `6ξ` = 6*ξ

    val η = atanh( sin(dλ) / sqrt(1 + t*t))
    val `2η` = 2*η
    val `4η` = 4*η
    val `6η` = 6*η

    val E = E0 + D*(η + (α1 * cos(`2ξ`)*sinh(`2η`)) + (α2 * cos(`4ξ`)*sinh(`4η`)) + (α3 * cos(`6ξ`)*sinh(`6η`)));
    val N = N0 + D*(ξ + (α1 * sin(`2ξ`)*cosh(`2η`)) + (α2 * sin(`4ξ`)*cosh(`4η`)) + (α3 * sin(`6ξ`)*cosh(`6η`)));

    Some(UtmPosition( utmZone, band, Kilometers(E), Kilometers(N)))
  }
}

case class UtmPosition (zone: Int, band: Char, easting: Length, northing: Length)