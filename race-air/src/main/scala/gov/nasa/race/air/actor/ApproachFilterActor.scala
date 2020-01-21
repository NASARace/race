/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.FilterActor
import gov.nasa.race.air.{TrackedAircraft, TrackedAircraftSeq}
import gov.nasa.race.air.filter.ApproachFilter
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.track.TrackDropped

import scala.collection.mutable.{HashMap => MHashMap}

/**
  * a specialized FilterActor that always uses a ApproachFilter and adds state based processing such
  * as TrackDropped emission and vertical rate filtering
  *
  * Note that ApproachFilter configuration uses our config object (since we always need it), but we can add
  * additional application specific filters such as callsign, time or destination
  */
class ApproachFilterActor(_conf: Config) extends FilterActor(_conf) {

  val candidates: MHashMap[String,TrackedAircraft] = MHashMap.empty

  // we always have a ApproachFilter as the first one, which is using the top config
  override def createFilters: Array[ConfigurableFilter] = new ApproachFilter(config) +: super.createFilters

  override def publishFiltered (msg: Any): Unit = {
    // we need to flatten input
    msg match {
      case acs: TrackedAircraftSeq[_] => acs.foreach( ac=> action(ac,pass(ac)))
      case _ => super.publishFiltered(msg)
    }
  }

  override def action (msg: Any, isPassing: Boolean): Unit = {
    if (isPassing) { // update the monitored list
      msg match {
        case ac: TrackedAircraft =>
          // check for consecutive climbing here
          candidates += ac.id -> ac
          publish(msg)

        case _ => // ignore
      }

    } else { // did not pass, check if we have to drop it from the monitored list
      msg match {
        case ac: TrackedAircraft =>
          if (candidates.contains(ac.id)){
            candidates -= ac.id
            publish(TrackDropped(ac.id, ac.cs, ac.date, ac.source))
          }
        case _ => // ignore
      }
    }
  }
}
