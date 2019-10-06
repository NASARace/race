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

import gov.nasa.race.air.AsdexTrack
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.Seq

/**
  * reg test for AsdexMsgParser
  */
class AsdexMsgParserSpec extends AnyFlatSpec with RaceSpec {

  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("asdexMsg.xml"))

  "a AsdexMsgParser" should "reproduce known values" in {
    val translator = new AsdexMsgParser
    translator.translate(xmlMsg) match {
      case Some(list: Seq[_]) =>
        list.foreach { e=>
          println(e)
          // do some sample value checks
          e match {
            case track: AsdexTrack =>
              track.id match {
                case "3382" => assert( track.position.altMeters.round == 518)
                case "1992" => assert (track.position.altitude.isUndefined)
                case "1833" => assert (track.heading.toDegrees.round == 309)
                case _ => // ignore
              }
            case other => fail(s"item not a AsdexTrack")
          }
        }

      case other => fail(s"parser failed to produce result: $other")
    }
  }
}
