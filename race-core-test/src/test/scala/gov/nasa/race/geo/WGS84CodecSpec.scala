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

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle.Degrees
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for WGS84 lat/lon compression/decompression
  */
class WGS84CodecSpec extends AnyFlatSpec with RaceSpec {

  final val eps = 0.000001
  final val nSuccessful = 100

  //--- continental US
  val positions: Gen[GeoPosition] = for {
    φ <- Gen.choose(10.0, 60.0)
    λ <- Gen.choose(-140.0, -40.0)
  } yield GeoPosition(Degrees(φ), Degrees(λ))

  "a WGS84Codec" should "reproduce lat/lon degrees within NAS to 6 digits" in {
    val codec = new WGS84Codec

    forAll (positions, minSuccessful(nSuccessful)) { p=>
      val lat = p.φ.toDegrees
      val lon = p.λ.toDegrees

      val v = codec.encode(lat,lon)
      codec.decode(v)

      val dLat = lat - codec.latDeg
      val dLon = lon - codec.lonDeg
      println(f"lat: $lat%.7f - ${codec.latDeg}%.7f = $dLat%.7f,   lon: $lon%.7f - ${codec.lonDeg}%.7f = $dLon%.7f")

      assert(Math.abs(dLat) < eps)
      assert(Math.abs(dLon) < eps)
    }
    println(s"passed $nSuccessful tests")
  }
}
