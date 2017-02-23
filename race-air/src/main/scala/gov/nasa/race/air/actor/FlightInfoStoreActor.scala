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
import gov.nasa.race.air._
import gov.nasa.race.core.Messages.{BusEvent, ChannelTopicAccept, ChannelTopicRelease, ChannelTopicRequest}
import gov.nasa.race.core.{ChannelTopicProvider, SubscribingRaceActor}


/**
  * an actor that holds a FlightInfoStore and reports entries on demand
  * via dynamic update sub-channels
  */
class FlightInfoStoreActor (val config: Config) extends ChannelTopicProvider with SubscribingRaceActor {

  var activeUpdates = Set.empty[String] // the cs'es we publish updates for

  def publish (fInfo: FlightInfo): Unit =  writeTo.foreach { baseChannel => publish( s"$baseChannel/${fInfo.cs}", fInfo) }

  val store = new FlightInfoStore {
    override protected def update (key: String, fInfo: FlightInfo): Unit ={
      super.update(key,fInfo)
      if (activeUpdates.contains(key)) publish(fInfo)
    }
  }
  val parser = new FlightInfoTfmParser(store)

  override def handleMessage = {
    case BusEvent(_, xmlMsg: String, _) => parser.parse(xmlMsg)

    case RequestFlightInfo(cs) =>
      store.get(cs) match {
        case Some(fi) => sender ! fi
        case None => sender ! NoSuchFlightInfo(cs)
      }
  }

  //--- the ChannelTopicProvider part
  override def isRequestAccepted (request: ChannelTopicRequest) = {
    request.channelTopic.topic match {
      case Some(FlightInfoUpdateRequest(cs)) => true
      case other => false
    }
  }

  override def gotAccept (accept: ChannelTopicAccept) = {
    accept.channelTopic.topic match {
      case Some(FlightInfoUpdateRequest(cs)) =>
        activeUpdates = activeUpdates + cs
        store.get(cs).foreach(publish)
      case other => // ignore
    }
  }

  override def gotRelease (release: ChannelTopicRelease) = {
    release.channelTopic.topic match {
      case rel@Some(FlightInfoUpdateRequest(cs)) =>
        if(!hasClientsForTopic(rel)) activeUpdates = activeUpdates - cs
      case other => // ignore
    }
  }
}
