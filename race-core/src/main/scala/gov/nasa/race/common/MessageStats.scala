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

import scalatags.Text.all._
import scalatags.generic.TypedTag
import scalatags.text.{Builder => STBuilder}

/**
  * XML message statistics
  * this is the object that is used during collection, it is NOT threadsafe so don't pass
  * it around to other actors or threads
  */
class MsgStats (val msgName: String) {
  var tLast: Long = 0     // timestamp of last rate base
  var lastCount: Int = 0  // msg count of last rate base

  var byteSize: Long = 0
  var count: Int = 0
  var avgMsgPerSec: Double = 0.0
  var peakMsgPerSec: Double = 0.0

  val pathMatches = MSortedMap.empty[String,PatternStats]
  val regexMatches = MSortedMap.empty[String,PatternStats]

  def update (tNow: Long, tElapsed: Long, lenBytes: Int)(implicit rateBaseMillis: Int) = {
    count += 1
    byteSize += lenBytes
    avgMsgPerSec = count * 1000.0 / tElapsed

    if (tNow - tLast >= rateBaseMillis) {
      peakMsgPerSec = Math.max(peakMsgPerSec, (count - lastCount) * 1000.0 / (tNow - tLast))
      tLast = tNow
      lastCount = count
    }
  }

  def snapshot: MsgStatsSnapshot = MsgStatsSnapshot(
    msgName,byteSize,count,avgMsgPerSec,peakMsgPerSec,
    mapIteratorToArray(pathMatches.valuesIterator,pathMatches.size)(_.snapshot),
    mapIteratorToArray(regexMatches.valuesIterator,regexMatches.size)(_.snapshot)
  )
}

/**
  * XML element statistics.
  * Those are recorded per message type
  */
class PatternStats(val pattern: String) {
  var count: Int = 0
  def snapshot = new PatternStatsSnapshot(pattern,count)
}


/**
  * this is the invariant version of MsgStats that can be processed asynchronously
  */
case class MsgStatsSnapshot (
  msgName: String,
  byteSize: Long,
  count: Int,
  avgMsgPerSec: Double,
  peakMsgPerSec: Double,
  paths: Array[PatternStatsSnapshot],  // element paths
  patterns: Array[PatternStatsSnapshot] // regex pattern matches
)

case class PatternStatsSnapshot(
  pattern: String,
  count: Int
)

class SubscriberMsgStats (val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                          val messages: Array[MsgStatsSnapshot]) extends Stats
                                    with ConsoleStats with HtmlStats {
  def writeToConsole(pw: PrintWriter) = {
    pw.println(consoleHeader)

    pw.println(s"observed channels: $channels")
    if (messages.nonEmpty) {
      var count = 0
      var avgRate = 0.0
      var peakRate = 0.0
      var byteSize = 0L

      pw.println("  count    msg/s   peak     size    avg   msg")
      pw.println("-------   ------ ------   ------ ------   --------------------------------------")
      for (m <- messages) {
        val memSize = FileUtils.sizeString(m.byteSize)
        val avgMemSize = FileUtils.sizeString((m.byteSize / m.count).toInt)
        pw.println(f"${m.count}%7d   ${m.avgMsgPerSec}%6.1f ${m.peakMsgPerSec}%6.1f   $memSize%6s $avgMemSize%6s   ${m.msgName}%s")
        m.paths.foreach( e => pw.println(f"${e.count}%46d ${StringUtils.capLength(e.pattern)(40)}%s"))
        m.patterns.foreach( e => pw.println(f"${e.count}%46d ${StringUtils.capLength(e.pattern)(40)}%s"))
        count += m.count
        avgRate += m.avgMsgPerSec
        peakRate += m.peakMsgPerSec
        byteSize += m.byteSize
      }

      if (messages.length > 1) {
        // otherwise there is no point printing summaries
        val memSize = FileUtils.sizeString(byteSize)
        val avgMemSize = if (count > 0) FileUtils.sizeString((byteSize / count).toInt) else 0

        pw.println("-------   ------ ------   ------ ------")
        pw.println(f"${count}%7d   ${avgRate}%6.1f ${peakRate}%6.1f   $memSize%6s $avgMemSize%6s")
      }
    }
    pw.println
  }


  def toHtml: TypedTag[STBuilder,String,String] = {
    def patternStats(s:PatternStatsSnapshot) = tr(
      td(cls:="right")(s.count),
      td(cls:="left")(s.pattern)
    )

    div(
      htmlTopicHeader(channels),
      table(
        tr(
          th("count"), th("msg/sec"), th("peak"), th("size"), th("avgSize"), th(cls:="left")("msg")
        ),
        for (m <- messages) yield tr(cls:="value top")(
          td(m.count),
          td(f"${m.avgMsgPerSec}%.1f"),
          td(f"${m.peakMsgPerSec}%.1f"),
          td(FileUtils.sizeString(m.byteSize)),
          td(FileUtils.sizeString((m.byteSize / m.count).toInt)),
          td(cls:="left")(
            m.msgName,
            table(cls:="noBorder")(
              for (s <- m.paths) yield patternStats(s),
              for (s <- m.patterns) yield patternStats(s)
            )
          )
        ),
        if (messages.size > 1) {
          var count = 0
          var avgRate = 0.0
          var peakRate = 0.0
          var byteSize = 0L

          for (m <- messages) {
            count += m.count
            avgRate += m.avgMsgPerSec
            peakRate += m.peakMsgPerSec
            byteSize += m.byteSize
          }

          tr(
            td(count),
            td(f"$avgRate%.1f"),
            td(f"$peakRate%.1f"),
            td(FileUtils.sizeString(byteSize)),
            td(if (count > 0) FileUtils.sizeString((byteSize/count).toInt) else "")
          )
        } else p()
      )
    )
  }
}
