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

import gov.nasa.race.air.FlightPos
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.Seq

/**
  * reg test for OpenSky2FlightPos translator
  */
class OpenSky2FlightPosSpec extends AnyFlatSpec with RaceSpec {

  "a OpenSky2FlightPos translator" should "reproduce known values" in {
    val msg = fileContentsAsUTF8String(baseResourceFile("opensky.json")).get

    val translator = new OpenSky2FlightPos()
    val res = translator.translate(msg)

    res match {
      case Some(list:Seq[_]) =>
        list.foreach { println }
        assert(list.size == 33) // there are a number of state vectors that are incomplete

        //--- some sanity checks
        var fpos = list(0).asInstanceOf[FlightPos]
        assert(fpos.cs == "SWR5181")
        assert(fpos.position.altMeters.round == 9197)
        println(s"check ${fpos.cs} Ok.")

        fpos = list.last.asInstanceOf[FlightPos]
        assert(fpos.cs == "SWR44KX")
        assert(fpos.speed.toKnots.round == 157)
        println(s"check ${fpos.cs} Ok.")

      case other => fail(s"failed to parse messages, result=$other")
    }
  }
}
