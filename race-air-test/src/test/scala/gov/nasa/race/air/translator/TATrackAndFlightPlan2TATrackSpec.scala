/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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

import gov.nasa.race.air.TATrack
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.Seq

/**
  * regression test for TATrackAndFlightPlan2TATrack translator
  */
class TATrackAndFlightPlan2TATrackSpec extends AnyFlatSpec with RaceSpec {
  final val EPS = 0.000001
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("tais.xml"))

  behavior of "TATrackAndFlightPlan2TATrack translator"

  "translator" should "reproduce known values" in {
    val translator = new TATrackAndFlightPlan2TATrack(createConfig("attach-rev = true"))

    val res = translator.translate(xmlMsg)
    res match {
      case Some(list: Seq[TATrack]) =>
        list.foreach {
          println
        }
      case None => fail("failed to produce result")
      case other => fail(s"failed to parse messages: $other")
    }
  }
}
