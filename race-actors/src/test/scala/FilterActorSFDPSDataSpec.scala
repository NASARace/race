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

package gov.nasa.race.actors.filters

import akka.event.Logging
import gov.nasa.race.core.RaceActorSpec
import org.joda.time.DateTime
import org.scalatest.WordSpecLike


/**
 * unit tests for the actor gov.nasa.race.actors.filters.FilterActor using
 * NasFlightsARTCCFilter to filter a sfdps message by artcc.
 */
class FilterActorSFDPSDataSpec extends RaceActorSpec with WordSpecLike {

  "A FilterActor using NasFlightsARTCCFilter" should {
    "filter a sfdps msg using artcc" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val filterActor = addTestActor(classOf[FilterActor], "filter", createConfig(
          """read-from = "sfdps"
             write-to = "filtered"
             filters = [
               { class = ".data.filters.NasFlightsARTCCFilter"
                 artcc = "ZDC"
               }
             ] """))

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        // <2do> this only tests filter instantiation in the context of a FilterActor,
        // this should be a normal unit test for Filter, not FilterActor - add real pub tests & negatives
        val msg = scala.io.Source.fromFile("race-actors/src/test/scala/msgs/sfdps.xml").mkString
        for(filter <- actor(filterActor).filters) {
          filter.pass(msg) should be( true)
        }
        println("filter passed")

        terminateTestActors
      }
    }
  }
}
