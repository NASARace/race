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
import gov.nasa.race.common.{PatternStatsSnapshot, Stats, SubscriberMsgStats}
import gov.nasa.race.util.FileUtils

import scalatags.Text.all.{cls, div, p, table, td, th, tr, _}

/**
  * a HTMLStatsFormatter for MessageStats
  */
class HtmlMessageStatsFormatter (config: Config) extends HtmlStatsFormatter {

  override def toHtml(stats: Stats): Option[HtmlArtifacts] = {
    stats match {
      case msgStats: SubscriberMsgStats =>
        Some(HtmlArtifacts(msgStatsToHtml(msgStats),HtmlStats.noResources))
      case _ => None
    }
  }

  def patternStats(s:PatternStatsSnapshot) = tr(
    td(cls:="right")(s.count),
    td(cls:="left")(s.pattern)
  )

  def msgStatsToHtml(ms: SubscriberMsgStats) = {
    div(
      HtmlStats.htmlTopicHeader(ms.topic,ms.channels,ms.elapsedMillis),
      table(
        tr(
          th("count"), th("msg/sec"), th("peak"), th("size"), th("avgSize"), th(cls:="left")("msg")
        ),
        for (m <- ms.messages) yield tr(cls:="value top")(
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
        if (ms.messages.size > 1) {
          var count = 0
          var avgRate = 0.0
          var peakRate = 0.0
          var byteSize = 0L

          for (m <- ms.messages) {
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
