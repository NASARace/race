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

package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core._

/**
  * trait for PublishingRaceActors that support filters
  * note that filters are static, this versions does not support dynamic addition/removal
  */
trait FilteringPublisher extends PublishingRaceActor {

  val config: Config // actor config to be provided by concrete actor class

  val passUnfiltered = passUnfliteredDefault // do we let pass if there is no filter set?
  var filters = createFilters // optional
  val matchAll = config.getBooleanOrElse("match-all", defaultMatchAll) // default is to let pass if any of the filters passes

  val publishFiltered: (Any=>Unit) = filters.length match {
    case 0 => (msg) => { action(msg, passUnfiltered) }
    case 1 => (msg) => { action(msg, filters(0).pass(msg)) }
    case _ => (msg) => { action(msg, if (matchAll) !filters.exists(!_.pass(msg)) else filters.exists(_.pass(msg))) }
  }

  def defaultMatchAll = false

  // override this if we have specific filters
  def createFilters: Array[ConfigurableFilter] = config.getConfigArray("filters").map(getConfigurable[ConfigurableFilter])

  // override this if we only want to let messages pass if we have filters set
  def passUnfliteredDefault = config.getBooleanOrElse("pass-unfiltered", true)

  /** override if there is selective publishing or additional action */
  def action (msg: Any, isPassing: Boolean) = if (isPassing) publish(msg)

  /**
    * this is what concrete actor handleMessage implementations can use to generically publish filtered
    */
  def handleFilteringPublisherMessage: Receive = {
    // NOTE - don't match ChannelMessage because that would break system channels/messages (e.g. ChannelTopics)
    case BusEvent(chan,msg:Any,_) => publishFiltered(msg)
  }
}
