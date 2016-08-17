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

package gov.nasa.race.data

import gov.nasa.race.common.RaceSpec
import squants.space._
import GreatCircle._

import org.scalatest._
import org.scalatest.prop._
import org.scalacheck._

class GreatCircleSpec extends FlatSpec with RaceSpec {
  // test data
  val SJC = LatLonPos(Degrees(37.363947), Degrees(-121.928937))
  val IND = LatLonPos(Degrees(39.716859), Degrees(-86.295595))

  behavior of "GreatCircle algorithms"

  //--- simple test cases
  "bearings" should "comply with known values" in {
    initialBearing(SJC, IND).toDegrees should be(74.0 +- 0.01)
    finalBearing(SJC, IND).toDegrees should be(96.65 +- 0.01)
  }

  "distance" should "comply with known values" in {
    distance(SJC, IND).toKilometers should be(3090.0 +- 0.5)
  }

  //--- properties (scalacheck based)
  val positions: Gen[LatLonPos] = for {
    φ <- Gen.choose(-180.0, 180.0)
    λ <- Gen.choose(-180.0, 180.0)
  } yield LatLonPos(Degrees(φ), Degrees(λ))

  "distance" should "be commutative" in {
    forAll((positions, "p1"), (positions, "p2")) { (p1: LatLonPos, p2: LatLonPos) =>
      (distance(p1, p2) - distance(p2, p1)).toKilometers should be(0.0 +- 0.5)
    }
  }

  "final bearing" should "be inverse init bearing" in {
    forAll((positions, "p1"), (positions, "p2")) { (p1: LatLonPos, p2: LatLonPos) =>
      //println(s"p1=$p1, p2=$p2")
      (finalBearing(p1, p2) - normalize(initialBearing(p2, p1) - Degrees(180))).toDegrees should be(0.0 +- 0.5)
    }
  }
}

