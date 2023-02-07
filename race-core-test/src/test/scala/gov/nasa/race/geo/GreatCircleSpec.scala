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
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length.Meters
import org.scalacheck._
import org.scalatest.flatspec.AnyFlatSpec

class GreatCircleSpec extends AnyFlatSpec with RaceSpec {
  // test data
  val SJC = GeoPosition(Degrees(37.363947), Degrees(-121.928937))
  val IND = GeoPosition(Degrees(39.716859), Degrees(-86.295595))

  behavior of "GreatCircle algorithms"

  //--- simple test cases
  "bearings" should "comply with known values" in {
    val initBearing = GreatCircle.initialBearing(SJC, IND)
    println(s"init-bearing $SJC -> $IND = $initBearing  (expected ~74.0)")
    initBearing.toNormalizedDegrees should be(74.0 +- 0.01)

    val finalBearing = GreatCircle.finalBearing(SJC, IND)
    println(s"final-bearing $SJC -> $IND = $finalBearing  (expected ~96.65)")
    finalBearing.toNormalizedDegrees should be(96.65 +- 0.01)
  }

  "distance" should "comply with known values" in {
    GreatCircle.distance(SJC, IND).toKilometers should be(3090.0 +- 0.5)
  }

  //--- properties (scalacheck based)
  val positions: Gen[GeoPosition] = for {
    φ <- Gen.choose(-180.0, 180.0)
    λ <- Gen.choose(-180.0, 180.0)
  } yield GeoPosition(Degrees(φ), Degrees(λ))

  "distance" should "be commutative" in {
    forAll((positions, "p1"), (positions, "p2")) { (p1: GeoPosition, p2: GeoPosition) =>
      val d12 = GreatCircle.distance(p1, p2)
      val d21 = GreatCircle.distance(p2, p1)
      val delta = (d12 - d21).toKilometers
      println(s"$p1,$p2 => delta= $delta")

      delta should be(0.0 +- 0.5)
    }
  }

  "final bearing" should "be inverse init bearing" in {
    forAll((positions, "p1"), (positions, "p2")) { (p1: GeoPosition, p2: GeoPosition) =>
      //println(s"p1=$p1, p2=$p2")
      (GreatCircle.finalBearing(p1, p2) - (GreatCircle.initialBearing(p2, p1) - Degrees(180))).toNormalizedDegrees should be(0.0 +- 0.5)
    }
  }

  "along track distances" should "add up" in {
    val p1 = GeoPosition.fromDegrees(50,-120)
    val p2 = GeoPosition.fromDegrees(30, -120)
    val p = GeoPosition.fromDegrees(38,-110)

    val d = GreatCircle.distance2D(p1,p2)
    val d1 = GreatCircle.alongTrackDistance(p, p1, p2)
    val d2 = GreatCircle.alongTrackDistance(p, p2, p1)

    val dsum = d1 + d2
    val e = (d - dsum).abs
    println(s"adding along-track distances: ${d1.toRoundedMeters} + ${d2.toRoundedMeters} = ${d.toRoundedMeters} ? ${dsum.toRoundedMeters} -> error = $e")
    assert( e < Meters(1))
  }


  "cross track bearing" should "match known value" in {
    val maxErrDeg = 0.5

    def test (p: GeoPosition, p1: GeoPosition, p2: GeoPosition, degExpected: Double): Unit = {
      val b = GreatCircle.crossTrackBearing(p, p1, p2)
      println(s" $p1 -> $p2 ∡ $p : $b")
      val errDeg = Math.abs(b.toDegrees - degExpected)
      assert( errDeg < maxErrDeg)
    }

    var p1 = GeoPosition.fromDegrees(10,-120)
    var p2 = GeoPosition.fromDegrees(-10, -120)
    var p = GeoPosition.fromDegrees(0,-110)
    test(p, p1, p2, 90)

    p1 = GeoPosition.fromDegrees(0, 100)
    p2 = GeoPosition.fromDegrees(0, 110)
    p = GeoPosition.fromDegrees( 10, 105)
    test(p, p1, p2, 0)
  }
}

