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
import gov.nasa.race._
import gov.nasa.race.common.{MsgMatcher, MsgStats, MsgStatsData, MutUtf8Slice, PatternStatsData, SlicePathMatcher, StringDataBuffer, UTF8XmlPullParser2, Utf8Buffer}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent

import scala.collection.mutable.{SortedMap => MSortedMap}
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

  class Parser extends UTF8XmlPullParser2 {
    val pathMatcher = pathSpecs.map(SlicePathMatcher(_))

    def parse (bs: Array[Byte], off: Int, len: Int): MsgStatsData = {
      var msgStat: MsgStatsData = null

      if (initialize(bs,off,len)) {
        if (parseNextTag) {
          val topElem = tag.intern

          if (ignoreMessage(topElem)) return null

          msgStat = msgStats.getOrElseUpdate(topElem, new MsgStatsData(topElem))
          msgStat.update(updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, bs.length)

          if (pathMatcher.nonEmpty) {
            while (parseNextTag) {
              if (isStartTag) {
                var i = 0
                while (i < pathMatcher.length) { // avoid Iterator allocation
                  val pm = pathMatcher(i)
                  if (pathMatches(pm)){
                    val ps = pathSpecs(i)
                    val patternStat = msgStat.pathMatches.getOrElseUpdate(ps,new PatternStatsData(ps))
                    patternStat.count += 1
                  }
                  i += 1
                }
              }
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

  override def onRaceTick(): Unit = {
    if (reportEmptyStats || msgStats.nonEmpty) publish(snapshot)
  }

  // on demand initialized if we have to process Strings or use Regex
  private val sb: StringDataBuffer = new Utf8Buffer(0)
  private val cs: MutUtf8Slice = MutUtf8Slice.empty

  override def handleMessage = {
    case BusEvent(_,msg: Array[Byte],_) =>
      analyzeMsg(msg,0,msg.length)

    case BusEvent(_,msg: String,_) =>
      sb.encode( msg)
      analyzeMsg(sb.data,0,sb.len)
  }

  def analyzeMsg(bs: Array[Byte], off: Int, len: Int): Unit = {
    val msgStat = parser.parse(bs,off,len)
    if (msgStat != null && patterns.nonEmpty) {
      cs.set(bs,off,len)
      checkMatches(msgStat,cs)
    }
  }

  def checkMatches (msgStat: MsgStatsData, msg: CharSequence) = {
    MsgMatcher.findFirstMsgMatcher(msg,patterns) foreach { mc =>
      msgStat.regexMatches.getOrElseUpdate(mc.name,new PatternStatsData(mc.name)).count += 1
    }
  }

  def snapshot = new MsgStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart,
                                        mapIteratorToArray(msgStats.valuesIterator,msgStats.size)(_.snapshot))
}

