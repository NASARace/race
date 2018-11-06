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
import org.scalacheck._
import org.scalatest._

class GreatCircleSpec extends FlatSpec with RaceSpec {
  // test data
  val SJC = GeoPosition(Degrees(37.363947), Degrees(-121.928937))
  val IND = GeoPosition(Degrees(39.716859), Degrees(-86.295595))

  behavior of "GreatCircle algorithms"

  //--- simple test cases
  "bearings" should "comply with known values" in {
    GreatCircle.initialBearing(SJC, IND).toNormalizedDegrees should be(74.0 +- 0.01)
    GreatCircle.finalBearing(SJC, IND).toNormalizedDegrees should be(96.65 +- 0.01)
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
}

