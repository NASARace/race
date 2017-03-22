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
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.Stats
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ParentContext, RaceActorRec, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._
import scalatags.Text.all.{head => htmlHead, _}

import HtmlStats._

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

  var httpContent = HttpContent(blankPage,noResources)

  parent.addChild(RaceActorRec(parent.actorOf(Props(new RouteActor(config)),"statsRoute"),config))

  def blankPage = {
    html(
      htmlHeader,
      body(cls := cssClass)(
        p("no statistics yet..")
      )
    ).toString()
  }

  def htmlHeader =  htmlHead(
    meta(httpEquiv := "refresh", content := s"${refreshRate.toSeconds}"),
    link(rel := "stylesheet", href := "race.css")
  )

  class RouteActor (val config: Config) extends SubscribingRaceActor {
    val title = config.getStringOrElse("title", "Statistics")
    val formatters: Seq[HtmlStatsFormatter] = config.getConfigSeq("formatters").flatMap ( conf =>
      parent.newInstance[HtmlStatsFormatter](conf.getString("class"),Array(classOf[Config]),Array(conf))
    )
    val topics = MSortedMap.empty[String, Stats]

    info("$name created")

    override def handleMessage = {
      case BusEvent(_, stats: Stats, _) =>
        topics += stats.topic -> stats
        httpContent = renderPage
    }

    def renderPage: HttpContent = {
      val topicArtifacts = getTopicArtifacts

      val page = html(
        htmlHeader,
        body(cls := cssClass)(
          h1(title),
          topicArtifacts.map(_.html)
        )
      ).toString

      val resources = topicArtifacts.foldLeft(noResources)( (acc,t) => acc ++ t.resources )

      HttpContent(page,resources)
    }

    def getTopicArtifacts: Seq[HtmlArtifacts] = topics.values.toSeq.flatMap(getHtml)

    def getHtml (stats: Stats): Option[HtmlArtifacts] = {
      firstFlatMapped(formatters)(_.toHtml(stats)).orElse {
        stats match {
          case htmlStats: HtmlStats => Some(htmlStats.toHtml)
          case _ => None // give up, we don't know how to turn this into HTML
        }
      }
    }
  }

  override def route: Route = {
    get {
      pathPrefix("race") {
        path("statistics") {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, httpContent.htmlPage))
        } ~ path("race.css"){
          cssContent match {
            case Some(s) => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s))
            case None => complete((NotFound, "race.css"))
          }
        } ~ extractUnmatchedPath { remainingPath =>
          val key = remainingPath.tail.toString
          val content = httpContent
          content.htmlResources.get(key) match {
            case Some(resource) => complete(resource)
            case None => complete((NotFound, s"not found: $remainingPath"))
          }
        }
      }
    }
  }
}
