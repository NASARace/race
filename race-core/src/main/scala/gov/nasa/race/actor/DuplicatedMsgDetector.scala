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

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{MD5Checksum, MsgMatcher, PrintStats, StringDataBuffer, Utf8Buffer, XmlSource, XmlTopElementParser}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._

/**
  * a RaceActor that detects and reports duplicated messages
  *
  * Note this is a generic version that only compares MD5 String hashes, we don't parse XML or
  * even store the raw message text here. This means semantically identical messages with different
  * formatting would not be detected.
  *
  * To avoid memory leaks, this implementation only considers messages within a configurable
  * time window for each message hash
  */
class DuplicatedMsgDetector (val config: Config) extends StatsCollectorActor with ChannelOptionPublisher {
  val checkWindow = config.getFiniteDurationOrElse("check-window", 5.minutes).toMillis
  val classifiers = MsgMatcher.getMsgMatchers(config)

  val checksums = MSortedMap.empty[String,Long]
  val md5 = new MD5Checksum

  var sdb: StringDataBuffer = null // initialized on demand
  val msgParser = new XmlTopElementParser

  val msgStatsData = MSortedMap.empty[String,DupStatsData]  // per message (automatic)
  val catStatsData = MSortedMap.empty[String,DupStatsData]  // per configured categories

  def getStringDataBuffer (minCapacity: Int): StringDataBuffer = {
    if (sdb == null) {
      sdb = new Utf8Buffer(minCapacity)
    } else {
      sdb.clear()
    }
    sdb
  }

  override def onRaceTick(): Unit = {
    if (reportEmptyStats || catStatsData.nonEmpty || msgStatsData.nonEmpty) publish(snapshot)
    purgeOldChecksums
  }

  override def handleMessage = {
    case BusEvent(_, msg: String, _) => checkMessage(msg)
    case BusEvent(_, rawMsg: Array[Byte], _) => checkMessage(rawMsg, rawMsg.length)
  }

  def checkMessage (msg: String): Unit = {
    val sdb = getStringDataBuffer(msg.length * 3/2)
    sdb += msg
    checkMessage(sdb.data,sdb.len)
  }

  def checkMessage (msg: Array[Byte], len: Int): Unit = {
    def incStats (map: MSortedMap[String,DupStatsData], key: String, count: Int, dt: Long) = {
      val ds = map.getOrElseUpdate(key, new DupStatsData(key))
      ds.count += count
      ds.dtMillis += dt
    }

    val cs = md5.getHexChecksum(msg, 0, len)
    val tNow = updatedSimTimeMillis

    checksums.get(cs) match {
      case Some(tLast) =>
        val dt = tNow - tLast

        incStats(msgStatsData,getMessageType(msg,len),1,dt)

        classifiers.foreach{ c=>
          val matchCount = c.matchCount(msg,0,len)
          if (matchCount > 0) incStats(catStatsData,c.name,matchCount,dt)
        }

        publishToChannelOption("duplicatedMsg",msg)
        checksums += cs -> tNow // update time

      case None => checksums += cs -> tNow
    }
  }

  def snapshot = new DuplicateMsgStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart,
                                       mapIteratorToArray(msgStatsData.valuesIterator,msgStatsData.size)(_.snapshot),
                                       mapIteratorToArray(catStatsData.valuesIterator,catStatsData.size)(_.snapshot))

  def purgeOldChecksums = {
    val tNow = updatedSimTimeMillis
    checksums foreach { e =>
      if (tNow - e._2 > checkWindow) {
        checksums -= e._1
      }
    }
  }

  // override this if the message is not XML
  def getMessageType (msg: Array[Byte], len: Int): String = {
    if (msgParser.parseTopElement(msg,0,len)) msgParser.tag.intern else "?"
  }
}

class DupStatsData(val classifier: String) extends XmlSource with Cloneable {
  var count = 0
  var dtMillis = 0L

  def snapshot = super.clone.asInstanceOf[DupStatsData]

  override def toXML: String = {
    s"""<dup classifier="$classifier">
      <count>$count</count>
      <rate>$dtMillis</rate>
    </dup>"""
  }
}


class DuplicateMsgStats(val topic: String, val source: String, val takeMillis: Long, val elapsedMillis: Long,
                        val msgStats: Array[DupStatsData], val matchStats: Array[DupStatsData]) extends PrintStats {

  def printStats(pw: PrintWriter, category: String, sd: Array[DupStatsData]) = {
    if (sd.nonEmpty) {
      pw.print(  "     count     dup/sec   "); pw.println(category)
      pw.println("----------   ---------   -------------------------------------------")
      var nCount = 0
      for (m <- sd) {
        val avgDtSecs = m.dtMillis.toDouble / (m.count * 1000.0)
        pw.println(f"${m.count}%10d   $avgDtSecs%9.3f   ${m.classifier}")
        nCount += m.count
      }
      if (sd.length > 1){
        pw.println("----------")
        pw.println(f"$nCount%10d")
      }
    }
  }

  def printWith (pw:PrintWriter) = {
    printStats(pw,"message",msgStats)
    if (matchStats.nonEmpty) pw.println
    printStats(pw,"match category",matchStats)
  }

  override def xmlData: String = {
    s"""<dupStats>
      <msgs>
         ${msgStats.map(_.toXML).mkString("\n")}
      </msgs>
      <matches>
         ${matchStats.map(_.toXML).mkString("\n")}
      </matches>
    </dupStats>"""
  }
}
