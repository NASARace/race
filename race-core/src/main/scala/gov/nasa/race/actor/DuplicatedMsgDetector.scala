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
import gov.nasa.race.common.{MD5Checksum, MsgClassifier, PrintStats, XmlSource}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.FileWriterRaceActor
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.util.DateTimeUtils._

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
class DuplicatedMsgDetector (val config: Config) extends StatsCollectorActor with FileWriterRaceActor {
  final val unclassified = "unclassified"

  val checkWindow = config.getFiniteDurationOrElse("check-window", 5.minutes).toMillis

  val checksums = MSortedMap.empty[String,Long]
  val md5 = new MD5Checksum

  val classifiers = MsgClassifier.getClassifiers(config)
  val dupStats = MSortedMap.empty[String,DupStatsData]

  override def handleMessage = {
    case BusEvent(_, msg: String, _) => checkMessage(msg)
    case RaceTick =>
      if (reportEmptyStats || dupStats.nonEmpty) publish(snapshot)
      purgeOldChecksums
  }

  def checkMessage (msg: String) = {
    val cs = md5.getHexChecksum(msg)
    val tNow = updatedSimTimeMillis

    checksums.get(cs) match {
      case Some(tLast) =>
        ifSome(MsgClassifier.classify(msg,classifiers)) { c =>
          val ds = dupStats.getOrElseUpdate(c.name, new DupStatsData(c.name))
          ds.count += 1
          ds.dtMillis += tNow - tLast
          logDuplicate(msg, tNow, tLast)
        }
        checksums += cs -> tNow // update time

      case None => checksums += cs -> tNow
    }
  }

  def snapshot = new SubscriberDupStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart,
                                        mapIteratorToArray(dupStats.valuesIterator,dupStats.size)(_.snapshot))

  def purgeOldChecksums = {
    val tNow = updatedSimTimeMillis
    checksums foreach { e =>
      if (tNow - e._2 > checkWindow) {
        checksums -= e._1
      }
    }
  }

  def logDuplicate (msg: String, tNow: Long, tLast: Long) = {
    write(s"\n<!-- BEGIN DUPLICATED ${dateMillisToTTime(tLast)} ${dateMillisToTTime(tNow)} -->\n")
    write(msg)
    write("\n<!-- END DUPLICATED -->\n")
  }
}

class DupStatsData(val classifier: String) extends XmlSource with Cloneable {
  var count = 0
  var dtMillis = 0L

  def snapshot = super.clone.asInstanceOf[DupStatsData]

  override def toXML = {
    <dup classifier={classifier}>
      <count>{count}</count>
      <rate>{dtMillis}</rate>
    </dup>
  }
}


class SubscriberDupStats (val topic: String,  val source: String, val takeMillis: Long, val elapsedMillis: Long,
                          val messages: Array[DupStatsData]) extends PrintStats {

  def printWith (pw:PrintWriter) = {
    if (messages.nonEmpty) {
      pw.println("  count     avg sec   classifier")
      pw.println("-------   ---------   -------------------------------------------")
      for (m <- messages) {
        val avgDtSecs = m.dtMillis.toDouble / (m.count * 1000.0)
        pw.println(f"${m.count}%7d   $avgDtSecs%9.3f   ${m.classifier}")
      }
    }
  }

  override def xmlData = <dupStats>{messages.map(_.toXML)}</dupStats>
}
