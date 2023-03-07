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
  */
object Datum {

  @inline final def `√` (x: Double): Double = sqrt(x)
  @inline final def `∛` (x: Double): Double = pow(x, 3.33333333333333333333e-1d)

  /**
   * convert geodetic (lat,lon,alt) WGS84 coordinates into geocentric ECI (x,y,z) coordinates
   */
  def withECEF[U] (φ: Double, λ: Double, alt: Double)(fn: (Double,Double,Double)=>U): U = {
    //--- constants
    val a: Double = 6378137.0  // wgs84 semi-major
    val `e²`: Double = 0.006694379990197619
    val `b²/a²`: Double = 9.93305620009858682943e-1d

    val h = alt
    val sin_φ = sin(φ)
    val cos_φ = cos(φ)

    val `N(φ)` = a / `√`( 1.0 - `e²`* (sin_φ`²`))
    val `(N(φ)+h)cos(φ)` = (`N(φ)` + h)*cos_φ

    val x = `(N(φ)+h)cos(φ)` *  cos(λ)
    val y = `(N(φ)+h)cos(φ)` *  sin(λ)
    val z = (`b²/a²` * `N(φ)` + h) * sin_φ

    fn(x,y,z)
  }

  /**
   * closed form
   * Osen, K
   * Accurate Conversion of Earth-Fixed Earth-Centered Coordinates to Geodetic Coordinates
   * [Research Report] Norwegian University of Science and Technology. 2017
   * https://hal.archives-ouvertes.fr/hal-01704943v2
   *
   * this is more accurate than Olson but takes 1.4x longer
   */
    /*
  def _withWGS84[U](x: Double, y: Double, z: Double)(fun: (Double,Double,Double)=>U): U = {
    //--- constants
    val `1/a²`: Double = 2.45817225764733181057e-14d
    val `(1-e²)/a²`: Double = 2.44171631847341700642e-14d
    val `1/6`:Double = 1.66666666666666666667e-1d
    val `𝑙`: Double = 3.34718999507065852867e-3d
    val `𝑙²`: Double = 1.1203680863101116e-5d
    val Hmin: Double = 2.25010182030430273673e-14d
    val `e⁴`: Double = 4.48147234524044602618e-5d
    val `1/∛2`: Double = 7.93700525984099737380e-1d
    val `1-e²`: Double = 9.93305620009858682943e-1d

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

    fun( φ, λ, h)
  }
     */

  /**
   * Olson, D. K. (1996).
   * Converting Earth-Centered, Earth-Fixed Coordinates to Geodetic Coordinates.
   * IEEE Transactions on Aerospace and Electronic Systems, 32(1), 473–476. https://doi.org/10.1109/7.481290
   *
   * this is ~1.4x faster than Osen and roundtrip errors are still below 1e-10 so we pick this as default
   */
  def withWGS84[U](x: Double, y: Double, z: Double)(fun: (Double,Double,Double)=>U): U = {
    val a = 6378137.0
    val e2 = 6.6943799901377997e-3
    val a1 = 4.2697672707157535e+4
    val a2 = 1.8230912546075455e+9
    val a3 = 1.4291722289812413e+2
    val a4 = 4.5577281365188637e+9
    val a5 = 4.2840589930055659e+4
    val a6 = 9.9330562000986220e-1

    val zp = abs(z)
    val w2 = x*x + y*y
    val w = `√`(w2)
    val z2 = z*z
    val r2 = w2 + z2
    val r = `√`(r2)

    if (r >= 100000) {
      val lon = atan2(y,x)
      val s2 = z2/r2
      val c2 = w2/r2
      var u = a2/r
      var v = a3 - a4/r

      var c = 0.0
      var s = 0.0
      var ss = 0.0
      var lat = 0.0

      if (c2 > 0.3) {
        s = (zp/r)*(1.0 + c2*(a1 + u + s2*v)/r)
        lat = asin(s)
        ss = s*s
        c = `√`(1.0 - ss)
      } else {
        c = (w/r)*(1.0 - s2*(a5 - u - c2*v)/r)
        lat = acos(c)
        ss = 1.0 - c*c
        s = `√`(ss)
      }
      val g = 1.0 - e2*ss
      val rg = a / `√`(g)
      val rf = a6 * rg
      u = w - rg * c
      v = zp - rf * s
      val f = c * u + s * v
      val m = c * v - s * u
      val p = m / (rf / g + f)

      lat += p
      val h = f + m*p/2.0
      if (z < 0.0) lat = -lat

      fun( lat, lon, h)

    } else {
      fun( 0, 0, -1.0e+7)
    }
  }


  // high level converters

  @inline def wgs84ToECEF (pos: GeoPosition): XyzPos = withECEF(pos.lat.toRadians, pos.lon.toRadians, pos.altMeters) { (x,y,z)=>
    XyzPos.fromMeters(x,y,z)
  }
  @inline def wgs84ToECEF (pos: LatLonAlt): XyzPos = withECEF(pos.φ, pos.λ, pos.altitude) { (x,y,z)=>
    XyzPos.fromMeters(x,y,z)
  }
  @inline def wgs84ToECEF (pos: (Double,Double,Double)): XyzPos = withECEF(pos._1, pos._2, pos._3) { (x,y,z)=>
    XyzPos.fromMeters(x,y,z)
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
    val `1-e²`: Double = 9.93305620009858682943e-1d
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
    val `a²`: Double = 4.0680631590769e+13d
    val `b²`: Double = 4.04082999828157e+13d

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
    val `a²`: Double = 4.0680631590769e+13d
    val `b²`: Double = 4.04082999828157e+13d

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
    val `1-e²`: Double = 9.93305620009858682943e-1d
    Radians( atan(`1-e²` * Tan(φ)))
  }
  def gdToGcLatDeg(deg:Double): Double = geodeticToGeocentricLatitude(Degrees(deg)).toDegrees

  // length of 1 degree in N-S direction at latitude φ
  def latDegreeLength (φ: Angle): Length = {
    Meters( 111132.92 - 559.82 * Cos(φ * 2) + 1.175 * Cos(φ * 4) - 0.0023 * Cos(φ * 6))
  }

  // length of 1 degree in W-E direction at latitude φ
  def lonDegreeLength (φ: Angle): Length = {
    Meters(111412.84 * Cos(φ) - 93.5 * Cos(φ * 3) + 0.118 * Cos(φ * 5))
  }
}
