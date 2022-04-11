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

import akka.actor.ActorRef
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{ChannelTopicProvider, ChannelTopicRequest}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.ifSome

/**
  * common base type for SBS import/replay actors, which are ChannelTopicProviders for the configured station id
  */
trait SbsImporter extends ChannelTopicProvider {

  val stationId: String = config.getStringOrElse("station-id", "default") // the station we serve
  val stationLocation: Option[GeoPosition] = config.getOptionalGeoPosition("station-location") // where the station is located

  val stationChannel: Option[String] = config.getOptionalString("write-station-to") // where we publish station availability

  override def isRequestAccepted (request: ChannelTopicRequest): Boolean = {
    val channelTopic = request.channelTopic
    if (writeTo.contains(channelTopic.channel)){ // we don't respond to requests through stationChannel
      channelTopic.topic match {
        case Some(id: String) =>
          if (id == stationId) {
            info(s"accepting request for station $id")
            true
          } else false
        case other => false
      }
    } else false
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)){
      ifSome(stationChannel) { publish(_,AdsbStation(stationId,None,stationLocation,true)) }
      true
    } else false
  }
}
