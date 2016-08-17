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

package gov.nasa.race.actors.imports

import com.typesafe.config.{ConfigFactory, Config}
import gov.nasa.race.common._
import gov.nasa.race.common.ConfigurableFilter
import gov.nasa.race.core._
import gov.nasa.race.core.ChannelTopicProvider
import gov.nasa.race.core.Messages.{ChannelTopicRelease, ChannelTopicAccept, ChannelTopicRequest}
import gov.nasa.race.data.Airport

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

/**
  * a JMS import actor for SWIM asdexMsg messages.
  * This is a on-demand provider that only publishes messages for requested airports.
  * Filtering airports without clients has to be efficient since asdexMsg can have a high rate (>30/sec)
  */
class AsdexImportActor(config: Config) extends JMSImportActor(config) with AsdexImporter
