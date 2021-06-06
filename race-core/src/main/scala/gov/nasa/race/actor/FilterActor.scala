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
import gov.nasa.race.core.ChannelTopicRequest
import gov.nasa.race.core._

/**
  * actor that filters messages using a set of configurable filters
  *
  * this actor only publishes messages that pass. If we also need to publish the
  * ones that fail, use an EitherOrRouter
  */
class FilterActor (val config: Config) extends FilteringPublisher with SubscribingRaceActor with TransitiveChannelTopicProvider {
  override def handleMessage = handleFilteringPublisherMessage

  override def isRequestAccepted (request: ChannelTopicRequest) = {
    writeTo.contains(request.channelTopic.channel)
  }
}
