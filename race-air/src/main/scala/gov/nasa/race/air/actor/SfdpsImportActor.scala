/*
 * Copyright (c) 2019, United States Government, as represented by the
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
import gov.nasa.race.air.{ARTCC, ARTCCs}
import gov.nasa.race.air.translator.MessageCollectionParser
import gov.nasa.race.core.Messages.ChannelTopicRequest
import gov.nasa.race.core.{AccumulatingTopicIdProvider, ChannelTopicProvider}
import gov.nasa.race.jms.{JMSImportActor, TranslatingJMSImportActor}
import javax.jms.Message

trait ARTCCTopicIdMapper {
  def topicIdsOf(t: Any): Seq[String] = {
    t match {
      case artcc: ARTCC => Seq(artcc.id)
      case artccs: ARTCCs => artccs.map(_.id) // we could check if size > 1
      case id: String => if (ARTCC.artccs.contains(id)) Seq(id) else Seq.empty[String]
      case ids: Seq[_] => ids.map(_.toString).filter(ARTCC.artccs.contains)
      case _ => Seq.empty[String]
    }
  }
}

/**
  * specialized JMSImportActor for SWIM SFDPS MessageCollection XML messages
  * note that we can't hardwire the JMS config (authentication, URI, topic etc) since SWIM access might vary
  */
class FilteringSfdpsImportActor(config: Config) extends JMSImportActor(config) with TranslatingJMSImportActor
                                          with FlatFilteringPublisher with AccumulatingTopicIdProvider {

  class FilteringMessageCollectionParser extends MessageCollectionParser {
    override protected def filterSrc (srcId: String) = !matchesAnyServedTopicId(srcId)
  }

  val parser = new FilteringMessageCollectionParser
  parser.setElementsReusable(flatten)

  override def translate (msg: Message): Any = {
    parser.parseTracks(getContentSlice(msg))
  }

  override def topicIdsOf(t: Any): Seq[String] = t match {
    case artcc: ARTCC => Seq(artcc.id)
    case artccs: ARTCCs => artccs.map(_.id) // we could check if size > 1
    case _ => Seq.empty[String]
  }
}

/**
  * a SFDPSImportActor that translates and publishes all messages as soon as there is a
  * ChannelTopicSubscriber for any ARTCC
  */
class OnOffSfdpsImportActor(config: Config) extends JMSImportActor(config) with TranslatingJMSImportActor
                                   with FlatFilteringPublisher with ChannelTopicProvider {

  class OnOffMessageCollectionParser extends MessageCollectionParser {
    override protected def filterSrc (srcId: String) = !hasClients
  }

  val parser = new OnOffMessageCollectionParser
  parser.setElementsReusable(flatten)

  override def translate (msg: Message): Any = {
    parser.parseTracks(getContentSlice(msg))
  }

  override def isRequestAccepted(request: ChannelTopicRequest): Boolean = {
    request.channelTopic.topic match {
      case Some(_:ARTCC) => true // we accept all ARTCC requests
      case _ => false
    }
  }
}

/**
  * actor that unconditionally imports and translates all SFDPS MessageCollection messages
  */
class SfdpsImportActor(config: Config) extends JMSImportActor(config) with TranslatingJMSImportActor
                                           with FlatFilteringPublisher {
  val parser = new MessageCollectionParser
  parser.setElementsReusable(flatten)

  override def translate (msg: Message): Any = {
    parser.parseTracks(getContentSlice(msg))
  }
}