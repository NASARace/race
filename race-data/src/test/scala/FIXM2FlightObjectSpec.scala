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
import gov.nasa.race.data.translators.FIXM2FlightObject
import org.joda.time.DateTime
import org.scalatest.FlatSpec
import squants.motion.Knots
import squants.space.{Degrees, Feet}

class FIXM2FlightObjectSpec extends FlatSpec with RaceSpec {
  final val EPS = 0.000001
  val xmlMsg = fileContentsAsUTF8String(new File("race-data/src/test/scala/sfdps-message.xml"))


  behavior of "FIXM3Message2FlightObject translator"

  "translator" should "reproduce known values" in {
    val translator = new FIXM2FlightObject()
    val res = translator.translate(xmlMsg)
    res match {
      case Some(list:Seq[IdentifiableAircraft]) =>
        list.foreach { println }
      case other => fail("failed to parse messages")
    }
  }
}
