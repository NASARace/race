/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import gov.nasa.race.core.PublishingRaceActor
import gov.nasa.race.config.ConfigUtils._

/**
  * a PublishingRaceActor that can write to a optional channels that can be bounded
  *
  * This differs from the write-to config in PublishingRaceActor in that write-to channels all receive the same
  * message when calling `publish`, whereas write-to-option channels require explicit channel parameters for
  * publishing and only write to that channel, i.e. the caller has to know which channels might be configured
  *
  * Note also that implementers reference channels by a symbolic name, not the channel itself. Concrete application
  * channels should not be hard wired into actors
  */
trait ChannelOptionPublisher extends PublishingRaceActor {

  class ChannelEntry (val name: String, val channel: String, val max: Int, var n: Int) {
    def publishOptional (msg: Any): Unit = {
      if (max < 0 || (n < max)){
        n += 1
        publish(channel,msg)
      }
    }
  }

  val channelChoices = config.getConfigArray("write-to-option").foldLeft(Map.empty[String,ChannelEntry]){ (m, conf) =>
    val ce = new ChannelEntry(conf.getString("name"), conf.getString("channel"), conf.getIntOrElse("max-write",-1),0)
    m + (ce.name -> ce)
  }

  def publishToChannelOption(name: String, msg: Any) = {
    channelChoices.get(name) match {
      case Some(ce) => ce.publishOptional(msg)
      case None => debug(s"unknown channel choice $name")
    }
  }

  def hasChannelOptions = channelChoices.nonEmpty
  def hasChannelOption(name: String) = channelChoices.get(name).isDefined
}
