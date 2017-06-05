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
  * a PublishingRaceActor that can write to a alternative channels that can be bounded
  */
trait ChannelChoicePublisher extends PublishingRaceActor {

  class ChannelEntry (val name: String, val max: Int, var n: Int) {
    def publish (msg: Any): Unit = {
      if (max < 0 || (n < max)){
        n += 1
        publish(name,msg)
      }
    }
  }

  val channelChoices = config.getConfigArray("write-to-choices").foldLeft(Map.empty[String,ChannelEntry]){ (m, conf) =>
    val ce = new ChannelEntry(conf.getString("name"), conf.getIntOrElse("max-write",-1),0)
    m + (ce.name -> ce)
  }

  def publishToChannelChoice (channelName: String, msg: Any) = {
    channelChoices.get(channelName) match {
      case Some(ce) => ce.publish(msg)
      case None => info(s"unknown channel choice $channelName")
    }
  }

  def hasChannelChoices = channelChoices.nonEmpty
  def hasChannelChoice (channelName: String) = channelChoices.get(channelName).isDefined
}
