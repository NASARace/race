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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.FlatFilteringPublisher
import gov.nasa.race.air.{TRACON, TRACONs}
import gov.nasa.race.air.translator.TATrackAndFlightPlanParser
import gov.nasa.race.core.AccumulatingTopicIdProvider
import gov.nasa.race.jms.{JMSImportActor, TranslatingJMSImportActor}
import javax.jms.Message

trait TRACONTopicIdMapper {
  def topicIdsOf(t: Any): Seq[String] = {
    t match {
      case tracon: TRACON => Seq(tracon.id)
      case id: String => TRACON.getId(id).toList
      case ids: Seq[_] => ids.flatMap( _ match {
          case t: TRACON => Some(t.id)
          case s: String => TRACON.getId(s)
          case _ => None
        }).toList
      case _ => Seq.empty[String]
    }
  }
}

/**
  * a JMS import actor for SWIM TAIS (STDDS) messages.
  * This is a on-demand provider that only publishes messages for requested TRACONs.
  * Filtering tracons without clients has to be efficient since this stream can have a very high rate (>900msg/sec)
  */
class TaisImportActor(config: Config) extends JMSImportActor(config) with TranslatingJMSImportActor
            with FlatFilteringPublisher with AccumulatingTopicIdProvider with TRACONTopicIdMapper {

  //--- translation/parsing
  class FilteringTATrackAndFlightPlanParser extends TATrackAndFlightPlanParser {
    override protected def filterSrc (srcId: String) = {
      !servedTopicIds.contains(srcId)
    }
  }

  val parser = new FilteringTATrackAndFlightPlanParser
  parser.setElementsReusable(flatten) // if we flatten we don't have to make sure the collection is not reused

  override def translate (msg: Message): Any = {
    parser.parseTracks(getContentSlice(msg))
  }
}