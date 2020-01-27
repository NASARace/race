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

import gov.nasa.race.air.TaisTrack
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.Seq

/**
  * reg test for TATrackAndFlightPlanParser (based on XmlPullParser2)
  */
class TaisTrackAndFlightPlanParserSpec extends AnyFlatSpec with RaceSpec {
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("tais.xml"))

  behavior of "TATrackAndFlightPlan2TATrack translator"

  "translator" should "reproduce known values" in {
    val translator = new TATrackAndFlightPlanParser(createConfig("buffer-size = 8000"))

    val res = translator.translate(xmlMsg)
    res match {
      case Some(list: Seq[_]) =>
        list.foreach { e =>
          e match {
            case taTrack: TaisTrack =>
              println(taTrack)
              taTrack.id match {
                case "XYZ-1677" => assert(taTrack.heading.toDegrees.round == 261) // do some sanity check
                case "XYZ-146" => assert(taTrack.speed.toKnots.round == 139)
                case other => fail(s"result item has wrong id: ${taTrack.id}")
              }
            case other => fail(s"result item not a TATrack object: $other")
          }
        }
        assert(list.size == 2)
      case None => fail("failed to produce result")
      case other => fail(s"failed to parse messages: $other")
    }
  }

}
