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

import gov.nasa.race.air.FlightPos
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for SBS2FlightPosSpec
  */
class SBS2FlightPosSpec extends AnyFlatSpec with RaceSpec {

  behavior of "SBS2FlightPosSpec translator"

  "translator" should "produce known FlightPos once it accumulated all aircraft infos" in {
    val msg1 = "MSG,1,111,11111,AA2BC2,111111,2016/03/11,13:07:16.663,2016/03/11,13:07:16.626,UAL814  ,,,,,,,,,,,0"
    val msg4 = "MSG,4,111,11111,AA2BC2,111111,2016/03/11,13:07:16.663,2016/03/11,13:07:16.62,,,316,106,,,1536,,,,,0"
    val msg3 = "MSG,3,111,11111,AA2BC2,111111,2016/03/11,13:07:16.663,2016/03/11,13:07:16.62,,11025,,,37.17274,-122.03935,,,,,,0"

    val translator = new SBS2FlightPos
    translator.useTempCS = false // make sure we don't auto-generate cs

    println("--------- MSG1,MSG4,MSG3")
    assert (translator.translate(msg1).isEmpty)
    assert (translator.translate(msg4).isEmpty)
    val fpos143 = translator.translate(msg3)
    println(s"$fpos143")
    assert(fpos143.nonEmpty)

    println("--------- MSG4,MSG1,MSG3")
    SBS2FlightPos.clear
    assert (translator.translate(msg4).isEmpty)
    assert (translator.translate(msg1).isEmpty)
    val fpos413 = translator.translate(msg3)
    println(s"$fpos413")
    assert(fpos413.nonEmpty)
    assert(fpos143 == fpos413)

    println("--------- MSG3,MSG4,MSG1")
    SBS2FlightPos.clear
    assert (translator.translate(msg3).isEmpty)
    assert (translator.translate(msg4).isEmpty)
    val fpos341 = translator.translate(msg1)
    println(s"$fpos341")
    assert(fpos341.nonEmpty)
    assert(fpos341 == fpos413)
  }
}
