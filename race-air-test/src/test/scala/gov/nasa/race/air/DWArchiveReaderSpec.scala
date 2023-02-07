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
package gov.nasa.race.air

import java.io.FileInputStream

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

class DWArchiveReaderSpec extends AnyFlatSpec with RaceSpec {

  behavior of "DWArchiveReader subclasses"

  "AsdexDWArchiveReader" should "parse messages" in {
    val msg = baseResourceFile("dw-asdex.xml")
    val is = new FileInputStream(msg)
    val ar = new AsdexDWArchiveReader(is)

    var n = 0
    while (ar.hasMoreArchivedData){
      ar.readNextEntry() match {
        case Some(e) =>
          println(s"$n: ${e.date}")
          n += 1
        case None =>
      }
    }

    ar.close()
    assert(n == 2)
  }
}
