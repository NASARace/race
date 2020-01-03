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

package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.FlatFilteringPublisher
import gov.nasa.race.air.{Airport, Airports}
import gov.nasa.race.air.translator.{AsdexMsgParser, FullAsdexMsgParser}
import gov.nasa.race.core.AccumulatingTopicIdProvider
import gov.nasa.race.jms.{JMSImportActor, TranslatingJMSImportActor}
import javax.jms.Message

/**
  * a specialized JMS import actor for SWIM asdexMsg messages.
  * This is a on-demand provider that only publishes tracks for requested airports.
  * Filtering airports without clients has to be efficient since asdexMsg can have a high rate (>30/sec)
  */
class AsdexImportActor (config: Config) extends JMSImportActor(config) with TranslatingJMSImportActor
                    with FlatFilteringPublisher with AccumulatingTopicIdProvider {

  class FilteringAsdexMsgParser extends FullAsdexMsgParser {
    override protected def filterAirport (airportId: String) = !servedTopicIds.contains(airportId)
  }

  val parser = new FilteringAsdexMsgParser

  override def translate (msg: Message): Any = {
    parser.parseTracks(getContentSlice(msg))
  }

  override def topicIdsOf(t: Any): Seq[String] = t match {
    case airport: Airport => Seq(airport.id)
    case airports: Airports => airports.map(_.id)
    case _ => Seq.empty[String]
  }
}
