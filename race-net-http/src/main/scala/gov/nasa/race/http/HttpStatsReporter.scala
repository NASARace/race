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

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.Stats
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ParentActor, RaceContext}
import gov.nasa.race.http.HtmlStats._
import gov.nasa.race.util.{ClassLoaderUtils, FileUtils}
import scalatags.Text.all.{content, head => htmlHead, _}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

object HttpStatsReporter {
  type Data = SortedMap[String,Stats]
  val emptyData: Data = SortedMap.empty[String,Stats]
}
import gov.nasa.race.http.HttpStatsReporter._

/**
  * server routes to display statistics
  */
class HttpStatsReporter (val parent: ParentActor, val config: Config)
          extends SubscribingRaceRoute[Data] {

  val refreshRate = config.getFiniteDurationOrElse("refresh", 5.seconds)
  val cssClass = config.getStringOrElse("css-class", "race")
  val cssPath = config.getStringOrElse("css","race.css")
  val cssContent: Option[String] = FileUtils.fileContentsAsUTF8String(new File(cssPath)) orElse {
    FileUtils.resourceContentsAsUTF8String(getClass,cssPath)
  }

  val title = config.getStringOrElse("title", "Statistics")
  val formatters: Seq[HtmlStatsFormatter] = config.getConfigSeq("formatters").flatMap { conf =>
    val clsName = conf.getString("class")
    ClassLoaderUtils.newInstance[HtmlStatsFormatter](parent.system,clsName, Array(classOf[Config]), Array(conf))
  }

  var httpContent = HttpContent(blankPage,noResources)

  //--- the interface methods to provide

  override protected def instantiateActor: RaceRouteActor[SortedMap[String,Stats]] = {
    new HttpStatsReportActor(config,this)
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

  override def setData (newData: Data): Unit = {
    httpContent = renderPage(newData) // generated from data
  }

  //--- content generation

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

  def getTopicArtifacts(data: Data): Seq[HtmlArtifacts] = data.values.toSeq.flatMap(getHtml)

  def getHtml (stats: Stats): Option[HtmlArtifacts] = {
    firstFlatMapped(formatters)(_.toHtml(stats)).orElse {
      stats match {
        case htmlStats: HtmlStats => Some(htmlStats.toHtml)
        case _ => None // give up, we don't know how to turn this into HTML
      }
    }
  }

  def renderPage (data: Data): HttpContent = {
    val topicArtifacts = getTopicArtifacts(data)

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
}

/**
  * actor that obtains Stats data from RACE bus and updates associated HttpStatsReporter
  */
class HttpStatsReportActor (val config: Config, val routeInfo: SubscribingRaceRoute[Data])
                                                                 extends RaceRouteActor[Data] {
  var topics: Data = emptyData

  info(s"$name created")

  override def handleMessage = {
    case BusEvent(_, stats: Stats, _) =>
      topics = topics + (stats.topic -> stats)
      routeInfo.setData(topics)
  }
}