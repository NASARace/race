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

package gov.nasa.race.http

import com.typesafe.config.Config
import gov.nasa.race.common.{PatternStatsData, Stats, MsgStats}
import gov.nasa.race.util.FileUtils

import scalatags.Text.all.{cls, div, p, table, td, th, tr, _}

/**
  * a HTMLStatsFormatter for MessageStats
  */
class HtmlMessageStatsFormatter (config: Config) extends HtmlStatsFormatter {

  override def toHtml(stats: Stats): Option[HtmlArtifacts] = {
    stats match {
      case msgStats: MsgStats =>
        Some(HtmlArtifacts(msgStatsToHtml(msgStats),HtmlStats.noResources))
      case _ => None
    }
  }

  def patternStats(s:PatternStatsData) = tr(
    td(cls:="right")(s.count),
    td(cls:="left")(s.pattern)
  )

  def msgStatsToHtml(s: MsgStats) = {
    import gov.nasa.race.util.FileUtils.sizeString

    div(
      HtmlStats.htmlTopicHeader(s.topic,s.source,s.elapsedMillis),
      table(
        tr(
          th("count"), th(" "), th("msg/s"), th("peak"), th(" "), th("bytes/s"), th("peak"), th(" "), th("size"), th("avgSize"), th(" "), th(cls:="left")("msg")
        ),
        for (m <- s.messages) yield tr(cls:="value top")(
          td(m.count),
          td(""),
          td(f"${m.avgMsgPerSec}%.0f"),
          td(f"${m.peakMsgPerSec}%.0f"),
          td(""),
          td(sizeString(Math.round(m.avgBytesPerSec))),
          td(sizeString(Math.round(m.peakBytesPerSec))),
          td(""),
          td(sizeString(m.byteSize)),
          td(sizeString((m.byteSize / m.count).toInt)),
          td(""),
          td(cls:="left")(
            m.msgName,
            table(cls:="noBorder")(
              for (s <- m.paths) yield patternStats(s),
              for (s <- m.patterns) yield patternStats(s)
            )
          )
        ),
        if (s.messages.size > 1) {
          var count = 0
          var avgMps = 0.0
          var peakMps = 0.0
          var avgBps = 0.0
          var peakBps = 0.0
          var byteSize = 0L

          for (m <- s.messages) {
            count += m.count
            avgMps += m.avgMsgPerSec
            peakMps += m.peakMsgPerSec
            avgBps += m.avgBytesPerSec
            peakBps += m.peakBytesPerSec
            byteSize += m.byteSize
          }

          tr(
            td(count),
            td(""),
            td(f"$avgMps%.0f"),
            td(f"$peakMps%.0f"),
            td(""),
            td(sizeString(Math.round(avgBps))),
            td(sizeString(Math.round(peakBps))),
            td(""),
            td(FileUtils.sizeString(byteSize)),
            td(if (count > 0) FileUtils.sizeString((byteSize/count).toInt) else "")
          )
        } else p()
      )
    )
  }
}
