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

import java.io.PrintWriter

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.{PrintStatsFormatter, Stats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{PeriodicRaceActor, SubscribingRaceActor}

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._

/**
  * a mix-in for Stats reporter actors
  *
  * Since we couldn't type match on a generic Stats type due to type erasure (short of using shapeless), we try to make
  * the best of it by storing all Stats. That means the report implementation has to do the type checking
  *
  * Note that we can get Stats messages for different topics at any time so we don't report right away and
  * wait (up to a max delay) until we haven't received Stats updates for a configured time. For that reason
  * the reporter tickInterval should be smaller than the one used by the StatsCollectors
  */
trait StatsReporterActor extends SubscribingRaceActor with PeriodicRaceActor {

  // how long do we wait to print after receiving the last Stats message, to avoid printing in the middle of a Stats series
  val reportDelayMillis = config.getFiniteDurationOrElse("report-delay",defaultTickInterval).toMillis

  // upper bound for update delay
  val maxDelayMillis = config.getFiniteDurationOrElse("max-report-delay", 15.seconds).toMillis

  val topics = MSortedMap.empty[String,Stats]

  var lastStatsMillis: Long = 0 // when did we receive the last Stats update
  var lastReportMillis: Long = Long.MaxValue // when did we last report topics
  var hasNewStats = false

  def report: Unit  // to be provided by concrete actor

  // our default ticks, which should be finer granularity than StatsCollectors
  override def defaultTickInterval = 5.seconds
  override def defaultTickDelay = 5.seconds

  override def onStartRaceActor(originator: ActorRef) = {
    startScheduler
    super.onStartRaceActor(originator)
  }

  def handleStatsReporterMessage: Receive = {
    case BusEvent(_,stats:Stats,_) =>
      topics += stats.topic -> stats
      lastStatsMillis = System.currentTimeMillis
      hasNewStats = true

    case RaceTick =>
      if (hasNewStats) {
        val t = System.currentTimeMillis
        if (lastStatsMillis > 0) {
          if (((t - lastStatsMillis) > reportDelayMillis) || ((t - lastReportMillis) > maxDelayMillis)) {
            report
            lastReportMillis = t
            hasNewStats = false
          }
        }
      }
  }

  override def handleMessage = handleStatsReporterMessage
}

/**
  * a StatsReporterActor that produces print output and can be configured with formatters
  */
trait PrintStatsReporterActor extends StatsReporterActor {
  val pw: PrintWriter // to be provided by concrete class

  val formatters: Seq[PrintStatsFormatter] = config.getConfigSeq("formatters").flatMap ( conf =>
    newInstance[PrintStatsFormatter](conf.getString("class"),Array(classOf[Config]),Array(conf))
  )

  def handledByFormatter (s: Stats): Boolean = formatters.exists( _.write(pw,s) )
}