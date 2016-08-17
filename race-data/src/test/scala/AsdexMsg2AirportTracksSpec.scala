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

import gov.nasa.race.common._
import gov.nasa.race.common.FileUtils._
import gov.nasa.race.common.RaceSpec
import gov.nasa.race.data.translators.AsdexMsg2AirportTracks
import org.scalatest.FlatSpec

/**
  * unit test for AsdexMsg2AirportTracks translator
  */
class AsdexMsg2AirportTracksSpec extends FlatSpec with RaceSpec {

  val xmlMsg = fileContentsAsUTF8String(new File("race-data/src/test/scala/asdex.xml"))

  behavior of "AsdexMsg2AirportTracks translator"

  "translator" should "reproduce known values" in {
    val translator = new AsdexMsg2AirportTracks
    val res = translator.translate(xmlMsg)
    println(res)
    ifSome(res) { a => a.tracks.foreach(println) }
  }
}
