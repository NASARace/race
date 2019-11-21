/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.Dated
import gov.nasa.race.common.{ConfiguredTSStatsCollector, TSEntryData, TSStatsData}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}

import scala.concurrent.duration._

/**
  * a trait for actors that collect and report statistics
  */
trait StatsCollectorActor extends SubscribingRaceActor with PublishingRaceActor with ContinuousTimeRaceActor with PeriodicRaceActor {
  val config: Config

  val reportEmptyStats = config.getBooleanOrElse("report-empty", true)
  val title = config.getStringOrElse("title", name)
  var channels = ""

  override def defaultTickInterval = 10.seconds
  override def defaultTickDelay = 10.seconds

  override def onStartRaceActor(originator: ActorRef) = {
    channels = readFromAsString
    super.onStartRaceActor(originator)
  }
}


/**
  * a StatsCollectorActor for time series data
  */
trait TSStatsCollectorActor[K,O <: Dated,E <: TSEntryData[O],S <: TSStatsData[O,E]]
            extends StatsCollectorActor with ConfiguredTSStatsCollector[K,O,E,S]  {
  val statsData = createTSStatsData

  // those have to be provided by the concrete actor
  def createTSStatsData: S

  def statsSnapshot = snapshot(title,channels)
}
