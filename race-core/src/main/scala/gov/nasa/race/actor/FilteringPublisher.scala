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

import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core._

import scala.collection.Seq

/**
  * trait for PublishingRaceActors that support filters
  * note that filters are static, this versions does not support dynamic addition/removal
  */
trait FilteringPublisher extends PublishingRaceActor {

  val passUnfiltered = passUnfilteredDefault // do we let pass if there is no filter set?
  var filters: Array[ConfigurableFilter] = createFilters
  val matchAll = config.getBooleanOrElse("match-all", defaultMatchAll) // default is to let pass if any of the filters passes

  def publishFiltered (msg: Any): Unit = {
    if (msg != null && msg != None) action(msg,pass(msg)) // no point publishing null or None
  }

  def pass(msg: Any): Boolean = filters.length match {
    case 0 => passUnfiltered
    case 1 => filters(0).pass(msg)
    case _ => if (matchAll) !filters.exists(!_.pass(msg)) else filters.exists(_.pass(msg))
  }

  def defaultMatchAll = false

  // override this if we have specific filters
  def createFilters: Array[ConfigurableFilter] = getConfigurables("filters")

  // override this if we only want to let messages pass if we have filters set
  def passUnfilteredDefault: Boolean = config.getBooleanOrElse("pass-unfiltered", true)

  /** override if there is selective publishing or additional action */
  def action (msg: Any, isPassing: Boolean): Unit = {
    if (isPassing) {
      publish(msg)
    }
  }

  /**
    * this is what concrete actor handleMessage implementations can use to generically publish filtered
    */
  def handleFilteringPublisherMessage: Receive = {
    // NOTE - don't match ChannelMessage because that would break system channels/messages (e.g. ChannelTopics)
    case BusEvent(chan,msg:Any,_) => publishFiltered(msg)
  }
}

/**
  * a FilteringPublisher that can flatten a Seq of objects to be published
  */
trait FlatFilteringPublisher extends FilteringPublisher {

  val flatten = config.getBooleanOrElse("flatten", false)  // do we publish the Seq elements separately or as a Seq object

  override def publishFiltered (msg: Any): Unit = {
    msg match {
      case list: Seq[_] =>
        if (list.nonEmpty) {
          if (flatten) {
            list.foreach(super.publishFiltered)
          } else {
            super.publishFiltered(list)
          }
        }
      case o => super.publishFiltered(o)
    }
  }
}