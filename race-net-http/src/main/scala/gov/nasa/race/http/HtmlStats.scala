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

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import gov.nasa.race.common.Stats
import gov.nasa.race.util.DateTimeUtils.durationMillisToHMMSS

import scalatags.Text.all.{cls, h2, span, style, _}

object HtmlStats {
  val noResources = Map.empty[String,ToResponseMarshallable]

  def htmlTopicHeader(topic: String, source: String, elapsedMillis: Long) = h2(
    topic,
    span(style:="float:right;")(
      span(cls:="label")("source:"),
      span(cls:="value")(source),
      span(cls:="label")("elapsed:"),
      span(cls:="value")(durationMillisToHMMSS(elapsedMillis))
    )
  )
}

case class HttpContent (htmlPage: String, htmlResources: HtmlResources)

case class HtmlArtifacts (html: HtmlElement, resources: HtmlResources)

/**
  * a Stats type that knows how to produce HTML.
  *
  * We use a scalaTags specific TypedTag here because this ensures the generator
  * will produce HTML, and it avoids premature string allocation for fragments that
  * serve no other purpose than to be aggregated into complete HTML documents. The downside
  * is that we add a 3rd party library dependency to HtmlStats although it is not really
  * using it (which is why HtmlStats reside in race-net-http instead of race-core)
  */
trait HtmlStats extends Stats {
  def toHtml: HtmlArtifacts
}

trait HtmlStatsFormatter {
  def toHtml(stats: Stats): Option[HtmlArtifacts]
}