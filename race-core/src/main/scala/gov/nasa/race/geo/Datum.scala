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

package gov.nasa.race.geo

import gov.nasa.race.common._
import gov.nasa.race.uom.{Angle, Length}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Area._

import Math._
import scala.language.{postfixOps, reflectiveCalls}

/**
  * functions related to Datum conversion
  *
  * conversion between ECEF and WGS84 uses the closed form described in
  *           https://hal.archives-ouvertes.fr/hal-01704943v2
  */
object Datum {

  //--- constants and functions
  val `a²`: Double = 4.0680631590769e+13d
  val `b²`: Double = 4.04082999828157e+13d
  val `a/b`: Double = 1.0033640898209764d
  val `1-a²/b²`: Double = -0.006739496788261468d
  val `a²/c`: Double = 7.79540464078689228919e+7d
  val `b²/c²`: Double = 1.48379031586596594555e+2d
  val `1-e²`: Double = 9.93305620009858682943e-1d
  val `e⁴`: Double = 4.48147234524044602618e-5d
  val `(1-e²)/b`: Double = 1.56259921876129741211e-7d
  val `(1-e²)/a²`: Double = 2.44171631847341700642e-14d
  val `1/a²`: Double = 2.45817225764733181057e-14d
  val `𝑙`: Double = 3.34718999507065852867e-3d
  val `𝑙²`: Double = `𝑙` * `𝑙`
  val `2𝑙²`: Double = 2 * `𝑙²`
  val `4𝑙²`: Double = 4 * `𝑙²`
  val Hmin: Double = 2.25010182030430273673e-14d
  val `∛2`: Double = 1.259921049894873d
  val `1/∛2`: Double = 7.93700525984099737380e-1d
  val `1/3`: Double = 3.33333333333333333333e-1d
  val `1/6`:Double = 1.66666666666666666667e-1d

  @inline final def `√` (x: Double): Double = sqrt(x)
  @inline final def `∛` (x: Double): Double = pow(x, `1/3`)


  /**
    * convert geodetic (lat,lon,alt) WGS84 coordinates into geocentric ECI (x,y,z) coordinates
    *
    * from Astronomical Almanac pg. K12
    */
  def withECEF[U] (φ: Double, λ: Double, alt: Double)(fn: (Double,Double,Double)=>U): U = {
    val h = alt
    val cos_φ = cos(φ)
    val N = `a²/c` / `√`((cos_φ`²`) + `b²/c²`)
    val d = (N + h) * cos_φ

    val x = d * cos(λ)
    val y = d * sin(λ)
    val z = (N * `1-e²` + h) * sin(φ)

    fn(x,y,z)
  }

  def wgs84ToECEF (pos: GeoPosition): XyzPos = withECEF(pos.lat.toRadians, pos.lon.toRadians, pos.altMeters) { (x,y,z)=>
    XyzPos.fromMeters(x,y,z)
  }
  def wgs84ToECEF (pos: LatLonAlt): XyzPos = withECEF(pos.φ, pos.λ, pos.altitude) { (x,y,z)=>
    XyzPos.fromMeters(x,y,z)
  }
  def wgs84ToECEF (pos: (Double,Double,Double)): XyzPos = withECEF(pos._1, pos._2, pos._3) { (x,y,z)=>
    XyzPos.fromMeters(x,y,z)
  }

  def withWGS84[U](x: Double, y: Double, z: Double)(f: (Double,Double,Double)=>U): U = {
    val `w²` = x*x + y*y
    val m = `w²` * `1/a²`
    val n = (z`²`) * `(1-e²)/a²`
    val `m+n` = m + n

    val p = `1/6` * (`m+n` - `e⁴`)
    val G = m * n * `𝑙²`
    val H = 2 * (p`³`) + G

    if (H < Hmin) throw new RuntimeException("outside bounds")

    val C = `∛`(H + G + 2*`√`(H*G)) * `1/∛2`
    val i = -`𝑙²` - (`m+n`/ 2)
    val P = p`²`
    val β = i/3 - C - P/C
    val k = `𝑙²` * (`𝑙²` - `m+n`)

    //val t = `√`( `√`((β`²`) - k) - (β+i)/2) - (signum(m-n) * `√`(abs((β-i)/2)))
    val tl = `√`( `√`((β`²`) - k) - (β+i)/2)
    val tr = `√`(abs((β-i)/2))
    val t =  tl - (if (m < n) -tr else tr)

    val `2𝑙(m-n)` = 2*`𝑙`*(m - n)
    val F = (t`⁴`) + 2*i* (t`²`) + `2𝑙(m-n)`*t + k
    val `𝑑F/𝑑t` = 4*(t`³`) + 4*i*t + `2𝑙(m-n)`
    val `𝛥t` = -F / `𝑑F/𝑑t`
    val `t+𝛥t` = t + `𝛥t`

    val u = `t+𝛥t` + `𝑙`
    val v = `t+𝛥t` - `𝑙`
    val w = `√`(`w²`)
    val wv = w * v
    val zu = z * u
    val φ = atan2(zu, wv)

    val `1/uv` = 1 / (u*v)
    val `𝛥w` = w - (wv * `1/uv`)
    val `𝛥z` = z - (zu * `1-e²` * `1/uv`)
    val `𝛥a` = `√`((`𝛥w``²`) + (`𝛥z``²`))
    val h = signum(u - 1) * `𝛥a`

    val λ = atan2(y,x)

    f( φ, λ, h)
  }

  @inline def ecefToWGS84(pos: (Length,Length,Length)): GeoPosition =  withWGS84(pos._1.toMeters, pos._2.toMeters, pos._3.toMeters) { (φ,λ,h) =>
    GeoPosition.fromRadiansAndMeters(φ,λ,h)
  }
  @inline def ecefToWGS84(pos: Cartesian3Pos): GeoPosition = withWGS84(pos.xMeters,pos.yMeters,pos.zMeters) { (φ,λ,h) =>
    GeoPosition.fromRadiansAndMeters(φ,λ,h)
  }
  @inline def ecefToWGS84(pos: Cartesian3): GeoPosition = withWGS84(pos.x,pos.y,pos.z) { (φ,λ,h) =>
    GeoPosition.fromRadiansAndMeters(φ,λ,h)
  }

  def parallelDistance (pos1: GeoPosition, pos2: GeoPosition): Length = {
    val lon1 = pos1.lon
    val lon2 = pos2.lon
    val dLon = if (lon2 > lon1) (lon2 - lon1) else (lon1 - lon2)

    val mlat = (pos1.lat + pos2.lat) / 2
    val r = parallelRadius(mlat)

    Meters(dLon.toRadians * r)
  }

  def meridionalDistance (pos1: GeoPosition, pos2: GeoPosition): Length = {
    val lat1 = pos1.lat
    val lat2 = pos2.lat
    val dlat = if (lat2 > lat1) lat2 - lat1 else lat1 - lat2

    val mlat = (lat1 + lat2) / 2
    val r = meridionalCurvatureRadius(mlat)

    Meters(dlat.toRadians * r)
  }

  def geoCentricLatitude (lat: Angle): Angle = {
    Radians( atan( `1-e²` * Tan(lat)))
  }

  // φ is the geodetic latitude
  def earthRadius (φ: Angle): Length = {
    val cos_φ = Cos(φ)
    val sin_φ = Sin(φ)

    val n = ((RE_E2 * cos_φ)`²`) + ((RE_N2 * sin_φ)`²`)
    val d = ((RE_E * cos_φ)`²`) + ((RE_N * sin_φ)`²`)
    val r = `√`(n/d)

    Meters(r)
  }

  // get earth radius for given cartesian point
  def earthRadius (xyz: Cartesian3): Double = {
    val d2 = xyz.length2
    val c0 = (xyz.z `²`) / d2
    val c1 = ((xyz.x `²`) + (xyz.y `²`)) / d2
    val c2 = c0 / `b²` + c1 / `a²`

    `√`(1.0/c2)
  }

  def toEarthRadius (xyz: Cartesian3): Cartesian3 = {
    xyz * (earthRadius(xyz) / xyz.length)
  }

  def scaleToEarthRadius (xyz: MutXyz): Unit = {
    xyz *= (earthRadius(xyz) / xyz.length)
  }

  def earthRadius (xyz: XyzPos): Length = {
    val d2 = xyz.length2
    val c0 = (xyz.z.toMeters `²`) / d2
    val c1 = ((xyz.x.toMeters `²`) + (xyz.y.toMeters `²`)) / d2
    val c2 = c0 / `b²` + c1 / `a²`

    Meters(`√`(1.0/c2))
  }

  def toEarthRadius (xyz: XyzPos): XyzPos = {
    xyz * (earthRadius(xyz).toMeters / xyz.length)
  }

  // spherical approximation of earth
  def wgs84ToSphericalECEF(pos: GeoPosition): XyzPos = {
    val lat = pos.lat
    val lon = pos.lon

    val r = earthRadius(lat) + pos.altitude
    val d = Cos(lat) * r

    val z = Sin(lat) * r
    val x = Cos(lon) * d
    val y = Sin(lon) * d

    XyzPos(x,y,z)
  }

  def geodeticToGeocentricLatitude (φ: Angle): Angle = {
    Radians( atan(`1-e²` * Tan(φ)))
  }
  def gdToGcLatDeg(deg:Double): Double = geodeticToGeocentricLatitude(Degrees(deg)).toDegrees
}
