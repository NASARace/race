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

package gov.nasa.race.actors.metrics

import akka.event.Logging
import gov.nasa.race.common._
import gov.nasa.race.core.RaceActorSpec
import org.joda.time.DateTime
import org.scalatest.WordSpecLike
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * __[R-100.7.1]__ unit tests for the actor gov.nasa.race.actors.metrics.XMLMessageStats
 */
class XMLMessageStatsSpec extends RaceActorSpec with WordSpecLike {

  // list of all tags for sfdps.xml up to the 2nd level
  val list = List("<flightPlan>", "<departure>", "<flightIdentification>",
    "<supplementalData>", "<NasFlight>", "<arrival>", "<enRoute>",
    "<gufi>", "* interval *", "<controllingUnit>", "<flightStatus>",
    "<assignedAltitude>")
  val msg = scala.io.Source.fromFile("race-actors/src/test/scala/msgs/sfdps.xml").mkString
  val msgNum = 3

  "a XMLMessageStats actor" should {
    "count the right number of messages" in {
      runRaceActorSystem(Logging.WarningLevel) {
        val xmlStats = addTestActor(classOf[XMLMessageStats], "xmlStats", createConfig(
          """read-from = "sfdps"
           tag-level = 2
           report-interval = 0
           report-frequency = 0
           pathname = "results/sfdps/"
           write-to = "stats"
           facility = "ZAB"
          """))
        val xmlStatsActor = actor(xmlStats)

        printTestActors
        initializeTestActors
        startTestActors(DateTime.now)

        val t0 = System.currentTimeMillis()
        repeat(msgNum){
          publish("sfdps", msg)
        }
        print("waiting for actor to process messages..")
        processMessages(xmlStats, 5 seconds) // wait for the actor to process all pending messages
        val dt = (System.currentTimeMillis() - t0).toInt
        println(s"Ok ($dt msec)")

        //--- assertions

        print("checking number of total messages..")
        xmlStatsActor.totalMs.num should be(msgNum)
        println("Ok")

        print("checking that right number of messages/sub-messages are picked up..")
        xmlStatsActor.maps(0).size should be(12)
        for(tuple <- xmlStatsActor.maps(0)) {
          list should contain (tuple._1) // make sure the parser picks up the right tag names
          tuple._2.num should be(msgNum) // make sure each tag is parsed 3 times
        }
        println("Ok")

        print("checking correct msg rates..")
        xmlStatsActor.totalMs.computeRates(dt)  // <2do> see MessageStats comment - use a FiniteDuration to get msg/sec
        val msgRate = msgNum.toDouble / dt
        xmlStatsActor.totalMs.msgRate should be (msgRate +- 0.05)
        println(s"Ok ($msgRate)")

        terminateTestActors
      }
    }
  }
}
