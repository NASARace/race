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

import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentRaceActor, RaceActorEntry}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * an actor that represents a configurable http server
  *
  * Note that content and route management is done by its configured RouteInfo objects, which might have
  * actors by themselves. This is somewhat analogous to RaceViewerActor and RaceLayer/RaceLayerActor
  *
  * This actor itself does not yet subscribe from or publish to RACE channels, but we might add control and/or
  * stats channels in the future
  */
class HttpServer (val config: Config) extends ParentRaceActor {
  implicit val materializer = ActorMaterializer()

  val host = config.getStringOrElse("host", "localhost")
  val port = config.getIntOrElse("port", 8082)
  val serverTimeout = config.getFiniteDurationOrElse("server-timeout", 5.seconds)
  val routeInfos = createRouteInfos
  val childEntries = routeInfos.map(_.actorEntry).flatten

  val route = concat(routeInfos.map(_.route):_*)
  var binding: Option[Future[ServerBinding]] = None

  override def onStartRaceActor(originator: ActorRef) = {
    binding = Some(Http()(system).bindAndHandle(route, host, port))
    info(s"$name serving on http://$host:$port")
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(binding) { f =>
      f.flatMap(_.unbind())
      Await.ready(f, serverTimeout)
    }
    info(s"$name stopped serving")
    super.onTerminateRaceActor(originator)
  }

  def createRouteInfos: Seq[RaceRouteInfo] = {
    config.getOptionalConfigList("routes").foldLeft(Seq.empty[RaceRouteInfo]) { (seq,routeConf) =>
      val routeName = routeConf.getString("name")
      val routeClsName = routeConf.getString("class")
      info(s"creating route '$routeName': $routeClsName")
      newInstance[RaceRouteInfo](routeClsName,Array(classOf[Config]),Array(routeConf)) match {
        case Some(ri) => seq :+ ri
        case None => error(s"$route $routeName did not instantiate"); seq
      }
    }
  }
}

/**
  * base aggregate type for route infos, which consist of a akka.http Route and an optional (child) RaceActor
  */
trait RaceRouteInfo {
  val config: Config

  def route: Route
  def actorEntry: Option[RaceActorEntry] = None
}

class TestRouteInfo (val config: Config) extends RaceRouteInfo {
  val response = config.getStringOrElse("response", "Hello from RACE")

  def route = {
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, response))
      }
    }
  }
}