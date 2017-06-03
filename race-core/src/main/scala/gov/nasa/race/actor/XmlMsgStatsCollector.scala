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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{MsgMatcher, MsgStatsData, PatternStatsData, MsgStats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.XmlPullParser

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._
import scala.util.matching.Regex

/**
  * a SubscribingRaceActor that reports number, size and average / peak rates for each message
  * type it receives on its input channels
  *
  * Instances can be optionally configured with path matchers for XML message elements, such
  * as "ns5:MessageCollection/message/flight/enRoute/**/location/pos"
  *
  * XmlMsgStatsCollector does only collect and publish statistics. Reporting has to be done with
  * a dedicated actor
  *
  * Note that we don't use Akka Agents here although this is a classic synchronous state access
  * problem from a client's perspective. The rationale is that we assume reporting happens much less
  * frequently than statistics update, hence we cannot afford a immutable type during updates
  * (we don't want to allocate a new object just to increase a single Int)
  */
class XmlMsgStatsCollector (val config: Config) extends StatsCollectorActor {

  final val defaultRateBaseMillis = 2000 // get peak rate over 2 sec

  val pathSpecs = config.getStringArray("paths") // optional, if none we only parse top level
  val patterns = MsgMatcher.getMsgMatchers(config)
  val ignoreMessages = config.getStringArray("ignore").map(new Regex(_))

  // the time base we use for msg peak rate calculation
  // Note - this can't be calculated from the time we process a message since Akka might not schedule
  // this actor until it has a number of pending messages, which means there would be no time gap
  // and hence an infinite peak rate
  implicit val rateBaseMillis = config.getIntOrElse("rate-base", defaultRateBaseMillis)

  val msgStats = MSortedMap.empty[String,MsgStatsData]

  class Parser extends XmlPullParser {
    val pathQueries = pathSpecs map(ps => compileGlobPathQuery(ps.split("/")))
    setBuffer(new Array[Char](config.getIntOrElse("buffer-size", 4096))) // pre-alloc buffer

    def parse(input: String): MsgStatsData = {
      initializeBuffered(input)

      var isFirstElement = true
      var msgStat: MsgStatsData = null
      var done = false

      while (!done && parseNextElement) {
        if (isStartElement) {
          if (isFirstElement){
            if (ignoreMessage(tag)) {
              done = true
            } else {
              isFirstElement = false
              msgStat = msgStats.getOrElseUpdate(tag, new MsgStatsData(tag))
              msgStat.update(updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, input.length)
              if (pathQueries.isEmpty) done = true // no need to parse elements
            }

          } else { // we have pathQueries. Avoid allocation, this can be called frequently
            var idx = 0
            pathQueries foreach { pqId =>
              if (isMatchingPath(pqId)) {
                val pattern = pathSpecs(idx)
                val patternStat = msgStat.pathMatches.getOrElseUpdate(pattern,new PatternStatsData(pattern))
                patternStat.count += 1
              }
              idx += 1
            }
          }
        }
      }
      msgStat
    }
  }

  val parser = new Parser

  def ignoreMessage (msgName: String): Boolean = {
    ignoreMessages foreach { re => if (re.findFirstIn(msgName).isDefined) return true }
    false
  }

  override def handleMessage = {
    case BusEvent(_,msg: String,_) =>
      val msgStat = parser.parse(msg)
      if (msgStat != null && patterns.nonEmpty) checkMatches(msgStat,msg)

    case RaceTick =>
      if (reportEmptyStats || msgStats.nonEmpty) publish(snapshot)
  }

  def checkMatches (msgStat: MsgStatsData, msg: String) = {
    MsgMatcher.findFirstMsgMatcher(msg,patterns) foreach { mc =>
      msgStat.regexMatches.getOrElseUpdate(mc.name,new PatternStatsData(mc.name)).count += 1
    }
  }

  def snapshot = new MsgStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart,
                                        mapIteratorToArray(msgStats.valuesIterator,msgStats.size)(_.snapshot))
}

