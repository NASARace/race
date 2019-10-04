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
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.Seq

/**
  * reg test for SFDPSParser
  */
class SFDPSParserSpec extends AnyFlatSpec with RaceSpec {

  "a SFDPSParser" should "reproduce known values" in {
    val xmlMsg = fileContentsAsUTF8String(baseResourceFile("fixm.xml")).get

    val flightRE = "<flight ".r
    val nFlights = flightRE.findAllIn(xmlMsg).size - 1   // flight HBAL476 has no altitude and should not be reported

    val conf = "buffer-size = 100000"

    val translator = new SFDPSParser(createConfig(conf))
    val res = translator.translate(xmlMsg)
    res match {
      case Some(list:Seq[_]) =>
        list.foreach { println }
        assert(list.size == nFlights)
        println(s"all $nFlights valid FlightObjects accounted for (HBAL476 has no altitude)")
      case other => fail(s"failed to parse messages, result=$other")
    }
  }
}
