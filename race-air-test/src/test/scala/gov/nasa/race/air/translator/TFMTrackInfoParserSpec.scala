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
package gov.nasa.race.air.translator

import gov.nasa.race.air.TFMTrackInfoParser
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils._
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for FlightInfo, FlightInfoStore and FlightInfoTfmParser
  *
  * TODO - this still needs assertions
  */
class TFMTrackInfoParserSpec extends AnyFlatSpec with RaceSpec {
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("tfmdata.xml")).get

  behavior of "TFMTrackInfoParser"

  "a TFMTrackInfoParser" should "parse known values" in {
    val parser = new TFMTrackInfoParser

    val res = parser.parse(xmlMsg)
    res match {
      case Some(list) => list.foreach(println)
      case None => fail("no TrackInfos parsed")
    }
  }
}
