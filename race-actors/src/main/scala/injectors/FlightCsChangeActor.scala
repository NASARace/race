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

package gov.nasa.race.actors.injectors

import com.typesafe.config.Config
import gov.nasa.race.core.{BusEvent, SubscribingRaceActor, PublishingRaceActor}
import gov.nasa.race.data.{FlightCsChanged, FlightPos}
import scala.collection.mutable.{HashMap=>MHashMap}

/**
  * an actor that reads FlightPos messages from an input channel, and
  * writes them to an output channel, watching for changed callsigns.
  * If such a change is detected, it sends a corresponding FlightCsChanged message
  * *before* passing on the FlightPos, so that clients can clean up accordingly
  *
  * NOTE - read and write channels have to be separate, or otherwise we can't guarantee
  * message order for subscribers
  *
  * Since this is a message sequencer, it is a potential bottleneck (N input generators
  * all have to go through this actor). To mitigate, we could share the map between instances,
  * but it is not clear the required locking would not eat up any gains
  */
class FlightCsChangeActor(val config: Config)
                extends PublishingRaceActor with SubscribingRaceActor {
  var idMap = MHashMap.empty[String,String]  // flightId -> cs

  val writeTo = config.getString("write-to")

  override def handleMessage = {
    case e@BusEvent(_,fpos: FlightPos,originator) if originator != self =>
      val flightId = fpos.flightId
      val cs = fpos.cs

      val oldCS = idMap.getOrElseUpdate(flightId, cs) // get+check atomic through actor
      if (oldCS != cs) {
        idMap.update(flightId,cs)
        log.info(s"$name detected FlightCsChange: $oldCS->$cs")
        publish(writeTo, FlightCsChanged(flightId,cs, oldCS, fpos.date))
      }
      publishBusEvent(writeTo, e) // pass through FlightPos message, preserving originator

    case e@BusEvent(_,_,_) => publishBusEvent(writeTo, e) // pass all others though
  }
}
