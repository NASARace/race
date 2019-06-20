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

import gov.nasa.race.IdentifiableObject
import gov.nasa.race.air.FlightPos
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Speed._
import gov.nasa.race.util.FileUtils._
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.Seq

class FIXM2FlightObjectSpec extends AnyFlatSpec with RaceSpec {
  final val EPS = 0.000001

  behavior of "FIXM2FlightObject translator"

  "translator" should "reproduce known values" in {
    val xmlMsg = fileContentsAsUTF8String(baseResourceFile("fixm.xml")).get

    val flightRE = "<flight ".r
    val nFlights = flightRE.findAllIn(xmlMsg).size - 1   // flight HBAL476 has no altitude and should not be reported

    val translator = new FIXM2FlightObject()
    val res = translator.translate(xmlMsg)
    res match {
      case Some(list:Seq[IdentifiableObject]) =>
        list.foreach { println }
        assert(list.size == nFlights)
        println(s"all $nFlights valid FlightObjects accounted for (HBAL476 has no altitude)")
      case other => fail(s"failed to parse messages, result=$other")
    }
  }

  "translator" should "translate old NasFlight messages" in {
    val xmlMsg = fileContentsAsUTF8String(baseResourceFile("sfdps-nasflight.xml")).get
    val expected = new FlightPos(
      "647",
      "UAL1634",
      GeoPosition.fromDegreesAndFeet(37.898333, -79.169722, 35000.0),
      Knots(488.0),
      Degrees(86.47494027976148),
      UndefinedSpeed,
      new DateTime("2015-09-11T17:59:30Z")
    )

    val translator = new FIXM2FlightObject()
    val res = translator.translate(xmlMsg)
    println(res)

    res match {
      case Some(list:Seq[IdentifiableObject]) =>
        assert(list.size == 1)
        list.head match {
          case fpos: FlightPos =>
            fpos.cs should be(expected.cs)
            fpos.id should be(expected.id)

            fpos.position.λ.toDegrees should be(expected.position.λ.toDegrees +- EPS)
            fpos.position.φ.toDegrees should be(expected.position.φ.toDegrees +- EPS)
            fpos.position.altitude.toFeet should be(expected.position.altitude.toFeet +- EPS)

            fpos.speed.toKnots should be(expected.speed.toKnots +- EPS)
            fpos.heading.toDegrees should be(expected.heading.toDegrees +- EPS)
            fpos.date.getMillis should be(expected.date.getMillis)
            println("matches expected values")
          case _ => fail("result list does not contain FlightPos")
        }
      case _ => fail(s"result not a FlightPos: $res")
    }
  }
}
