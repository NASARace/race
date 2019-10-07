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

import gov.nasa.race.air.TFMTrack
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.Seq

/**
  * reg test for TfmDataServiceParser
  */
class TfmDataServiceParserSpec extends AnyFlatSpec with RaceSpec {
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("tfmDataService.xml"))

  "a TfmDataServiceParser" should "reproduce known values" in {
    val translator = new TfmDataServiceParser

    val res = translator.translate(xmlMsg)
    res match {
      case Some(list: Seq[_]) =>
        list.foreach { e =>
          println(e)
          e match {
            case t: TFMTrack =>
              t.cs match {
                case "N407CD" => assert(t.position.altMeters.round == 1951)
                case "ENY3608" => assert(t.speed.toMetersPerSecond.round == 259)
                case _ => // ignore
              }
            case other => fail(s"parsed item not a TFMTrack object: $other")
          }
        }
        assert(list.size == 36)
      case other => fail(s"wrong TfmDataServiceParser result: $other")
    }
  }
}
