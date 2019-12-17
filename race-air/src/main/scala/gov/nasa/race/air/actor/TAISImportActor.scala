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
import gov.nasa.race.air.{TATrack, Tracon}
import gov.nasa.race.air.translator.{FilteringTATrackAndFlightPlanParser, TATrackAndFlightPlanParser}
import gov.nasa.race.core.ChannelTopicProvider
import gov.nasa.race.core.Messages.{ChannelTopicAccept, ChannelTopicRelease, ChannelTopicRequest}
import gov.nasa.race.jms.TranslatingJMSImportActor
import javax.jms.Message

import scala.collection.mutable.ArrayBuffer

/**
  * a JMS import actor for SWIM TAIS (STDDS) messages.
  * This is a on-demand provider that only publishes messages for requested TRACONs.
  * Filtering tracons without clients has to be efficient since this stream can have a very high rate (>900msg/sec)
  */
class TAISImportActor(config: Config) extends TranslatingJMSImportActor(config)
                                    with FlatFilteringPublisher with ChannelTopicProvider {

  //--- translation/parsing

  val parser = new FilteringTATrackAndFlightPlanParser(filterTracon,filterTrack)
  parser.setTracksReUsable(flatten) // if we flatten we don't have to make sure the collection is copied

  override def translate (msg: Message): Any = {
    parser.parseTracks(getContentSlice(msg))
  }

  // those filters work during parsing, which is more efficient than pre-parsing (supports only
  // un-scoped text searches) or during publishing (very in-effective to filter tracons)
  def filterTracon (src: String): Boolean = !servedTracons.contains(src)
  def filterTrack (track: TATrack) = false // we don't filter TATracks

  //--- ChannelTopicProvider interface

  val servedTracons = new ArrayBuffer[String] // we don't use a set since there can be more than one client for the same topic

  override def isRequestAccepted(request: ChannelTopicRequest): Boolean = {
    val channelTopic = request.channelTopic
    if (writeTo.contains(channelTopic.channel)){
      channelTopic.topic match {
        case Some(_: Tracon) => true
        case _ => false
      }
    } else false
  }

  override def gotAccept (accept: ChannelTopicAccept) = {
    accept.channelTopic.topic match {
      case Some(tracon: Tracon) => servedTracons += tracon.id
      case _ => // we don't serve anything else
    }
  }
  override def gotRelease (release: ChannelTopicRelease) = {
    release.channelTopic.topic match {
      case Some(tracon: Tracon) => servedTracons -= tracon.id
      case _ => // we don't serve anything else
    }
  }
}