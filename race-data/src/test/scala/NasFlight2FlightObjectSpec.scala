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

import java.io.File

import gov.nasa.race.common.FileUtils._
import gov.nasa.race.common.RaceSpec
import gov.nasa.race.data.translators.NasFlight2FlightObject
import org.joda.time.DateTime
import org.scalatest.FlatSpec
import squants.motion.Knots
import squants.space.{Degrees, Feet}

/**
  * unit test for NasFlight2FlightObject message translator
  */
class NasFlight2FlightObjectSpec extends FlatSpec with RaceSpec {

  final val EPS = 0.000001
  val xmlMsg = fileContentsAsUTF8String(new File("race-data/src/test/scala/sfdps-nasflight.xml"))
  val expected = FlightPos(
    "647",
    "UAL1634",
    LatLonPos.fromDegrees(37.898333, -79.169722),
    Feet(35000.0),
    Knots(488.0),
    Degrees(86.47494027976148),
    new DateTime("2015-09-11T17:59:30Z")
  )

  behavior of "NasFlight2FlightObject translator"

  "translator" should "reproduce known values" in {
    val translator = new NasFlight2FlightObject()
    val res = translator.translate(xmlMsg)
    println(res)
    res match {
      case Some(fpos: FlightPos) =>
        fpos.cs should be( expected.cs)
        fpos.flightId should be (expected.flightId)
        fpos.altitude.toFeet should be (expected.altitude.toFeet +- EPS)
        fpos.speed.toKnots should be (expected.speed.toKnots +- EPS)
        fpos.position.λ.toDegrees should be (expected.position.λ.toDegrees +- EPS)
        fpos.position.φ.toDegrees should be (expected.position.φ.toDegrees +- EPS)
        fpos.heading.toDegrees should be (expected.heading.toDegrees +- EPS)
        fpos.date.getMillis should be (expected.date.getMillis)
        println("matches expected values")
      case _ => fail(s"result not a FlightPos: $res")
    }
  }
}
