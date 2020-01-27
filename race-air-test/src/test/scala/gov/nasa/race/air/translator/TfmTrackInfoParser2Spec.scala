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
package gov.nasa.race.air.translator

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.track.TrackInfos
import gov.nasa.race.util.FileUtils._
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for FlightInfo, FlightInfoStore and FlightInfoTfmParser
  *
  * TODO - this still needs assertions
  */
class TfmTrackInfoParser2Spec extends AnyFlatSpec with RaceSpec {
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("tfmDataService.xml")).get


  "a TfmTrackInfoParser2" should "parse known values" in {
    val parser = new TfmTrackInfoParser2

    val res = parser.translate(xmlMsg)
    res match {
      case Some(tis:TrackInfos) =>
        val tInfos = tis.tInfos
        tInfos.foreach(println)
        assert (tInfos.size == 36)

        for (ti <- tInfos) {
          ti.cs match {
            case "MRA609" => assert (ti.atd.toString == "2017-08-08T00:08:00.000Z")
            case "DAL138" => assert( ti.eta.toString == "2017-08-08T00:52:46.000Z")
              //... and more checks
            case _ => // nothing to check
          }
        }

      case Some(other) => fail("not a Seq: $other")
      case None => fail("no TrackInfos parsed")
    }
  }
}
