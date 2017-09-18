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

import com.typesafe.config.Config
import gov.nasa.race.core.Messages.{BusEvent, ChannelTopicAccept, ChannelTopicRelease, ChannelTopicRequest}
import gov.nasa.race.track._
import gov.nasa.race.core.{ChannelTopicProvider, RaceContext, SubscribingRaceActor}

/**
  * actor that encapsulates a TrackInfoStore which is initialized/updated by means of configured
  * TrackInfoReaders, and on-demand publishes updates for requested tracks
  */
class TrackInfoStoreActor (val config: Config) extends ChannelTopicProvider with SubscribingRaceActor {

  val store: TrackInfoStore = createStore
  val readers: Array[TrackInfoReader] = createReaders

  var activeUpdates = Set.empty[String] // the on-demand track ids we publish updates for

  def createStore = new DefaultTrackInfoStore
  def createReaders: Array[TrackInfoReader] = getConfigurables("readers")

  def publish (tInfo: TrackInfo): Unit =  writeTo.foreach { baseChannel => publish( s"$baseChannel/${tInfo.cs}", tInfo) }

  def updateStore (ti: TrackInfo) = {
    val cs = ti.cs
    store.add(cs, ti)
    if (activeUpdates.contains(cs)) publish(store.get(cs))
  }

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    readers.foreach { r =>
      r.initialize.foreach(updateStore)
    }
    super.onInitializeRaceActor(raceContext,actorConf)
  }

  override def handleMessage = {
    case BusEvent(_, msg: Any, _) =>
      readers.foreach { r =>
        r.readMessage(msg).foreach(updateStore)
      }

    case RequestTrackInfo(cs) => // a direct, one-time request
      store.get(cs) match {
        case Some(tInfo) => sender ! tInfo
        case None => sender ! NoSuchTrackInfo(cs)
      }
  }

  //--- the ChannelTopicProvider interface

  override def isRequestAccepted (request: ChannelTopicRequest) = {
    request.channelTopic.topic match {
      case Some(TrackInfoUpdateRequest(cs)) => true
      case _ => false
    }
  }

  override def gotAccept (accept: ChannelTopicAccept) = {
    accept.channelTopic.topic match {
      case Some(TrackInfoUpdateRequest(cs)) =>
        activeUpdates = activeUpdates + cs
        store.get(cs).foreach(publish)
      case _ => // ignore
    }
  }

  override def gotRelease (release: ChannelTopicRelease) = {
    release.channelTopic.topic match {
      case rel@Some(TrackInfoUpdateRequest(cs)) =>
        if(!hasClientsForTopic(rel)) activeUpdates = activeUpdates - cs
      case _ => // ignore
    }
  }
}
