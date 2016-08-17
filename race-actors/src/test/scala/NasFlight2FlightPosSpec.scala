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

package gov.nasa.race.actors.translators

import akka.event.Logging
import gov.nasa.race.core.{BusEvent, RaceActorSpec}
import gov.nasa.race.data.{FlightPos, LatLonPos}
import org.joda.time.DateTime
import org.scalatest.WordSpecLike
import squants.motion.{Knots, UsMilesPerHour}
import squants.space.{Degrees, Feet}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.math._


/**
 * unit tests for the actor gov.nasa.race.actors.translators.NasFlight2FlightPos
 */
class NasFlight2FlightPosSpec extends RaceActorSpec with WordSpecLike {

  "A NasFlight2FlightPos" should {
    "translate an xml msg to a FlightPos object" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val translator = addTestActor(classOf[TranslatorActor], "translator", createConfig(
          """read-from = "sfdps"
             write-to = "fpos"
             translator = {
               class = "gov.nasa.race.data.translators.NasFlight2FlightObject"
             } """))

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        val msg = scala.io.Source.fromFile("race-actors/src/test/scala/msgs/sfdps.xml").mkString
        expectBusMsg("fpos", 5 seconds, publish("sfdps", msg)){
          case BusEvent(channel,flightpos:FlightPos,originator) =>
            println(s"  actor: '$originator'\n  published: $flightpos\n  to channel: '$channel'")
            val fpos = FlightPos(flightId="647",
                                 cs="UAL1634",
                                 position=LatLonPos(Degrees(37.898333),Degrees(-79.169722)),
                                 altitude=Feet(35000.0),
                                 speed=Knots(488.0),
                                 heading=Degrees(atan2(487.0,30.0).toDegrees),
                                 date=DateTime.parse("2015-09-11T17:59:30Z"))
            channel should be("fpos")
            flightpos should be(fpos)
            originator should be(translator)
        }

        terminateTestActors
      }
    }
  }
}
