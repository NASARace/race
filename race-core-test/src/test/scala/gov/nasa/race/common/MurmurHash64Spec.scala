/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.Set

/**
  * reg test for MurmurHash64
  */
class MurmurHash64Spec extends AnyFlatSpec with RaceSpec {

  val names = Seq[String](
    "AA001", "AA002", "AA1001", "AA1111", "AA1",
    "SWA8762", "SWA8912", "SWA129", "SWA9999",
    "DAL7", "DAL70", "DAL700", "DAL7000"
  )

  "MurmurHash64" should "produce unique hash values for known data" in {
    val seen = Set.empty[Long]

    names.foreach { id =>
      val hBytes = MurmurHash64.hash(id.getBytes)
      val hChars = MurmurHash64.hash(id.toCharArray)

      //assert (!seen(hBytes))
      seen += hBytes

      println(s"$id : ($hBytes, $hChars)")
    }
  }
}
