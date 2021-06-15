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
package gov.nasa.race.common

import java.io.PrintWriter
import gov.nasa.race.mapIteratorToArray
import gov.nasa.race.util.{FileUtils, StringUtils}
import scala.collection.mutable.{SortedMap => MSortedMap}

/**
  * XML message statistics
  * this is the object that is used during collection, it is NOT threadsafe so don't pass
  * it around to other actors or threads
  */
class MsgStatsData(val msgName: String) {
  var tLast: Long = 0     // timestamp of last rate base
  var lastCount: Int = 0  // msg count of last rate base
  var lastByteSize: Long = 0  // byte size of last rate base

  var byteSize: Long = 0
  var count: Int = 0
  var avgMsgPerSec: Double = 0.0
  var peakMsgPerSec: Double = 0.0
  var avgBytesPerSec: Double = 0.0
  var peakBytesPerSec: Double = 0.0

  val pathMatches = MSortedMap.empty[String,PatternStatsData]
  val regexMatches = MSortedMap.empty[String,PatternStatsData]

  def update (tNow: Long, tElapsed: Long, lenBytes: Int)(implicit rateBaseMillis: Int) = {
    count += 1
    byteSize += lenBytes

    val t = 1000.0 / tElapsed
    avgMsgPerSec = count * t
    avgBytesPerSec = byteSize * t

    if (tNow - tLast >= rateBaseMillis) {
      val dt = 1000.0 / (tNow - tLast)

      peakMsgPerSec = Math.max(peakMsgPerSec, (count - lastCount) * dt)
      lastCount = count

      peakBytesPerSec = Math.max(peakBytesPerSec, (byteSize - lastByteSize) * dt)
      lastByteSize = byteSize

      tLast = tNow
    }
  }

  def snapshot: MsgStatsDataSnapshot = MsgStatsDataSnapshot(
    msgName,count,byteSize,avgMsgPerSec,peakMsgPerSec,avgBytesPerSec,peakBytesPerSec,
    mapIteratorToArray(pathMatches.valuesIterator,pathMatches.size)(_.snapshot),
    mapIteratorToArray(regexMatches.valuesIterator,regexMatches.size)(_.snapshot)
  )
}

/**
  * this is the invariant version of MsgStats that can be processed asynchronously
  */
case class MsgStatsDataSnapshot(
  msgName: String,
  count: Int,
  byteSize: Long,
  avgMsgPerSec: Double,
  peakMsgPerSec: Double,
  avgBytesPerSec: Double,
  peakBytesPerSec: Double,
  paths: Array[PatternStatsData],  // element paths
  patterns: Array[PatternStatsData] // regex pattern matches
) extends XmlSource {

  override def toXML: String = {
    f"""    <msg name="$msgName">
      <count>$count</count>
      <bytes>$byteSize</bytes>
      <avgMsgPerSec>${avgMsgPerSec%.1f}</avgMsgPerSec>
      <peakMsgPerSec>${peakMsgPerSec%.1f}</peakMsgPerSec>
      <avgBytesPerSec>${avgBytesPerSec%.0f}</avgBytesPerSec>
      <peakBytesPerSec>${peakBytesPerSec%.0f}</peakBytesPerSec>
      <paths>
        ${paths.map(_.toXML).mkString("\n        ")}
      </paths>
      <patterns>
        ${patterns.map(_.toXML).mkString("\n        ")}
      </patterns>
    </msg>"""
  }
}


class MsgStats(val topic: String, val source: String, val takeMillis: Long, val elapsedMillis: Long,
               val messages: Array[MsgStatsDataSnapshot]) extends PrintStats {

  override def printWith (pw: PrintWriter) = {
    if (messages.nonEmpty) {
      var count = 0
      var avgMps = 0.0
      var peakMps = 0.0
      var avgBps = 0.0
      var peakBps = 0.0
      var byteSize = 0L

      pw.println("     count     msg/s    peak    byte/s    peak        size     avg   msg")
      pw.println("----------   ------- -------   ------- -------   --------- -------   ---------------------------------------")
      for (m <- messages) {
        val bps = FileUtils.sizeString(Math.round(m.avgBytesPerSec))
        val bpsPeak = FileUtils.sizeString(Math.round(m.peakBytesPerSec))
        val memSize = FileUtils.sizeString(m.byteSize)
        val avgMemSize = FileUtils.sizeString((m.byteSize / m.count).toInt)
        pw.println(f"${m.count}%10d   ${m.avgMsgPerSec}%7.0f ${m.peakMsgPerSec}%7.0f   $bps%7s $bpsPeak%7s   $memSize%9s $avgMemSize%7s   ${m.msgName}%s")
        m.paths.foreach( e => pw.println(f"${e.count}%80d ${StringUtils.capLength(e.pattern)(30)}%s"))
        m.patterns.foreach( e => pw.println(f"${e.count}%80d ${StringUtils.capLength(e.pattern)(30)}%s"))
        count += m.count
        avgMps += m.avgMsgPerSec
        peakMps += m.peakMsgPerSec
        avgBps += m.avgBytesPerSec
        peakBps += m.peakBytesPerSec
        byteSize += m.byteSize
      }

      if (messages.length > 1) {
        val bps = FileUtils.sizeString(Math.round(avgBps))
        val bpsPeak = FileUtils.sizeString(Math.round(peakBps))
        val memSize = FileUtils.sizeString(byteSize)
        val avgMemSize = if (count > 0) FileUtils.sizeString((byteSize / count).toInt) else 0

        pw.println("----------   ------- -------   ------- -------   --------- -------")
        pw.println(f"${count}%10d   ${avgMps}%7.0f ${peakMps}%7.0f   $bps%7s $bpsPeak%7s   $memSize%9s $avgMemSize%7s")
      }
    }
  }

  override def xmlData = s"""<msgStats>${messages.map(_.toXML).mkString("")}</msgStats>"""
}
