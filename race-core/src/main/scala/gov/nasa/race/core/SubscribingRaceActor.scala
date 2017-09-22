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

import akka.actor.ActorRef
import com.typesafe.config.Config
import scala.collection.mutable.{Set => MutableSet}
import gov.nasa.race.config.ConfigUtils._


/**
 * a RaceActor that can subscribe to the Bus
 */
trait SubscribingRaceActor extends RaceActor {
  var readFrom = MutableSet.empty[String]

  //--- pre-init channel setting
  def addSubscription (channel: String*) = readFrom ++= channel
  def addSubscriptions (channels: Seq[String]) = readFrom ++= channels

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    readFrom ++= actorConf.getOptionalStringList("read-from")
    readFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onInitializeRaceActor(raceContext,actorConf)
  }

  // we add new channels and (re-)subscribe to everything since old channels might now be global
  override def onReInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    val newChannels = actorConf.getOptionalStringList("read-from")
    // re-subscription is benign in case we already were subscribed (bus keeps subscribers as sets)
    readFrom ++= newChannels
    //newChannels.foreach { channel => busFor(channel).subscribe(self,channel) }
    readFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onReInitializeRaceActor(raceContext,actorConf)
  }

  //--- dynamic subscriptions
  def subscribe(channel: String) = {
    readFrom += channel
    busFor(channel).subscribe(self,channel)
  }
  def unsubscribe(channel: String) = {
    readFrom -= channel
    busFor(channel).unsubscribe(self,channel)
  }

  def unsubscribeAll: Unit = {
    readFrom.foreach { channel => busFor(channel).unsubscribe(self,channel) }
    readFrom.clear
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    unsubscribeAll
    super.onTerminateRaceActor(originator)
  }

  def readFromAsString = readFrom.mkString(",")
}
