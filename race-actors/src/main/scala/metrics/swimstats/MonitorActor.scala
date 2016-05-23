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

package gov.nasa.race.actors.metrics.swimstats

import akka.actor.ActorRef
import gov.nasa.race.core._

trait MonitorActor extends SubscribingRaceActor with PublishingRaceActor {
  var map = Map[String, Integer]()

  def getFacility(msg: Any): String

  def monitorMsg(msg: Any, facility: String): Unit = {

    if(facility.length>0) {
      if (map.contains(facility)) {
        map += (facility -> {
          map.get(facility).get + 1
        })
      }
      else {
        map += (facility -> 1)
      }
    }
  }

  override def terminateRaceActor (originator: ActorRef) = {
    super.terminateRaceActor(originator)

    log.info(s"${name} terminating")

    println(s"----------- list of facilities -----------")
    if(map.nonEmpty) {
      var i = 0
      for(e <- map) {
        i+=1
        println(s"${i}. $e")
      }
    } else {
      println(s"$map\n\n")
    }
  }
}
