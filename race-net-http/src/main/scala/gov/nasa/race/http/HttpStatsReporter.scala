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

import java.io.File

import akka.actor.Props
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.config.Config
import gov.nasa.race.common.{HtmlStats, Stats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ParentContext, RaceActorRec, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._
import scalatags.Text.all.{head => htmlHead, _}


/**
  * server routes to display statistics
  */
class HttpStatsReporter (val parent: ParentContext, val config: Config) extends RaceRouteInfo {

  val refreshRate = config.getFiniteDurationOrElse("refresh", 5.seconds)
  val cssClass = config.getStringOrElse("css-class", "race")

  val cssPath = config.getStringOrElse("css","race.css")
  val cssContent: Option[String] = FileUtils.fileContentsAsUTF8String(new File(cssPath)) orElse {
    FileUtils.resourceContentsAsUTF8String(getClass,cssPath)
  }

  var statsPage = html(
    htmlHeader,
    body(cls := cssClass)(
      p("no statistics yet..")
    )
  ).toString()

  parent.addChild(RaceActorRec(parent.actorOf(Props(new RouteActor(config)),"statsRoute"),config))

  def htmlHeader =  htmlHead(
    meta(httpEquiv := "refresh", content := s"${refreshRate.toSeconds}"),
    link(rel := "stylesheet", href := "race.css")
  )

  class RouteActor (val config: Config) extends SubscribingRaceActor {
    val topics = MSortedMap.empty[String, HtmlStats]

    info("$name created")

    override def handleMessage = {
      case BusEvent(_, stats: HtmlStats, _) =>
        topics += stats.topic -> stats
        statsPage = renderPage
      case BusEvent(_,other: Stats, _) => info(s"unhandled stats type ignored: ${other.getClass}")
    }

    def renderPage: String = {
      html(
        htmlHeader,
        body(cls := cssClass)(
          h1("Statistics"),
          topics.values.toSeq.map(_.toHtml)
        )
      ).toString
    }
  }

  override def route: Route = {
    get {
      pathPrefix("race") {
        path("statistics") {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, statsPage))
        } ~ path("race.css"){
          cssContent match {
            case Some(s) => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s))
            case None => complete((NotFound, "Not here!"))
          }
        }
      }
    }
  }
}
