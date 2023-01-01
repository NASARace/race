/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import gov.nasa.race.common.Xyz
import gov.nasa.race.geo.Datum.gdToGcLatDeg
import gov.nasa.race.uom.Length.Meters

/**
 * test for geo coordinate approximations
 */
object ApproxTest {

  //--- test data

  //val p0 = GeoPosition.fromDegreesAndMeters( 68.437576, -93.205424, 0.0)  // gt start
  //val p1 = GeoPosition.fromDegreesAndMeters( 6.115970,-119.528259, 0.0)   // gt end
  //val p = GeoPosition.fromDegreesAndMeters(34.672192,-118.592438, 0.0)    // hotspot (xt)

  val p0 = GeoPosition.fromDegreesAndMeters( 72.918287, -96.875506, 838383.8)  // gt start
  val p1 = GeoPosition.fromDegreesAndMeters( 11.149099,-129.486649, 829041.8)   // gt end
  val p = GeoPosition.fromDegreesAndMeters(37.062199,-122.219063, 0.0)    // hotspot (xt)

  def testECEF (pos: GeoPosition): Unit = {
    println(s"\n--- converting    $pos")

    val ecef = Datum.wgs84ToECEF(pos)
    println(s"wgs84 to ECEF: $ecef")

    val posʹ = Datum.ecefToWGS84(ecef)
    println(s"wgs84' from ECEF: $posʹ")
  }

  def testSphericalApprox (pos: GeoPosition): Unit = {
    println(s"\n--- spherical approximation of $pos")

    val ecef = Datum.wgs84ToECEF(pos)
    println(s"wgs84 to ECEF: $ecef")

    val ecefʹ = Datum.wgs84ToSphericalECEF(pos)
    println(s"spher to ECEF: $ecefʹ")

    val v1 = ecef.toXyz
    val v2 = ecefʹ.toXyz
    val d = (v1-v2).length
    println(s"delta = $d")
  }

  def testCrossTrackPoint(): Unit = {
    val a = Datum.wgs84ToECEF(p0.to2d).toXyz
    val b = Datum.wgs84ToECEF(p1.to2d).toXyz
    val c = Datum.wgs84ToECEF(p).toXyz

    println(s"@@ a.length= ${a.length}")
    println(s"@@ b.length= ${b.length}")

    val n = a.cross(b).unit
    val dist = c.dot(n) // from C to plane ab

    val d = c - n*dist  // intersection of normal through C and plane ab
    val cpʹ = Datum.ecefToWGS84(d) // D as geodetic pos (note this is inside globe)

    val dlen = d.length
    val er = Datum.earthRadius(cpʹ.φ).toMeters
    val dʹ = d * (er/dlen)
    val cd = Datum.ecefToWGS84(dʹ)
    println(s"dlen: $dlen, er: $er, cd: $cd")

    val cp = GeoPosition(cpʹ.φ, cpʹ.λ) // respective ground track (note it has same lat/lon as cpʹ)
    println(s"cpʹ-c x n: ${(c - d).dot(a)}   (should be 0)")

    println(s"t-midpoint: ${GreatCircle.midPoint(p0, p1)}")
  }

  def main(args: Array[String]): Unit = {
    //testECEF(p0)
    //testECEF(p1)
    //testSphericalApprox(p0)

    testCrossTrackPoint()
  }
}
