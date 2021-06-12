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
package gov.nasa.race.core

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, SyncRequest}
import gov.nasa.race.util.ArrayUtils

/**
 * a RaceActor that can publish to the Bus
 */
trait PublishingRaceActor extends RaceActor {
  var writeTo: Array[String] = Array.empty

  def writeToAsString = writeTo.mkString(",")

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    writeTo = actorConf.getStrings("write-to")
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  // we just add the new channels
  override def onReInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    writeTo = ArrayUtils.addUniques(writeTo, actorConf.getStrings("write-to"))
    super.onReInitializeRaceActor(raceContext, actorConf)
  }

  // publish on all configured channels
  def publish (msg: Any): Unit = {
    writeTo.foreach { publish(_,msg) }
  }

  @inline def publish (channel: String, msg: Any): Unit = {
    busFor(channel).publish( BusEvent(channel, msg, self))
  }

  //--- in case we can reuse the same BusEvent

  def publishBusEvent (e: BusEvent): Unit = {
    writeTo.foreach { publishBusEvent(_,e) }
  }

  // can be used for re-publishing BusEvents on a different channel
  def publishBusEvent (otherChannel: String, e: BusEvent): Unit = {
    val be = if (e.channel == otherChannel) e else e.copy(channel=otherChannel)
    busFor(otherChannel).publish(be)
  }


  def publishSyncRequest (tag: Any, responderType: Option[Class[_]] = None): Unit = {
    publish(SyncRequest(self,tag, responderType))
  }

  def hasPublishingChannels = writeTo.nonEmpty
}
