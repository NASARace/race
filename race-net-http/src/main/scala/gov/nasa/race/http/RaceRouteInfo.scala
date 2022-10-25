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

import akka.actor.Actor.Receive
import akka.actor.{ActorRef, Cancellable}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.SubConfigurable
import gov.nasa.race.core.{ConfigLoggable, ParentActor, RaceActorSystem, RaceDataClient, RaceDataClientMessage}
import gov.nasa.race.util.StringUtils
import scalatags.Text

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
  * base type for route infos, which consist of an akka.http Route and an optional RaceActor
  * to collect data used for the associated response content
  *
  * concrete RaceRouteInfo implementations have to provide a constructor that takes 2 args:
  *     (parent: ParentActor, config: Config)
  */
trait RaceRouteInfo extends SubConfigurable with ConfigLoggable {
  val parent: ParentActor
  val config: Config
  val name = config.getStringOrElse("name", getClass.getSimpleName)

  // we need these for the ConfigLoggable mixin
  val pathName = s"${parent.name}/$name"
  def system = parent.system

  override def clAnchor: Any = system

  val requestPrefix: String = getRequestPrefix
  val requestPrefixMatcher = PathMatchers.separateOnSlashes(requestPrefix)

  private var _isHttps = false // this is what the server chooses and tells us during initialization
  def isHttps = _isHttps

  def shouldUseHttps = false  // this is what tells the server if we need https

  def getRequestPrefix: String = config.getStringOrElse("request-prefix", name)

  // this is the main function that defines the public (user) routes for this RaceRouteInfo object
  // it should be collected in reverse order of trait linearization (last ones first)
  // note this has to be the identity element for route accumulation since this is a root type
  def route: Route = reject

  // this can extend public routes with private ones used from within server responses
  // (such as login/logout and common asset routes)
  def completeRoute: Route = route

  def protocol: String = if (_isHttps || shouldUseHttps) "https://" else "http://"

  // copy scheme, authority and user from request and add the requestPrefix
  def requestPrefixUrl (requestUrl: String): String = {
    s"${StringUtils.upToNth(requestUrl, '/', 3)}/$requestPrefix"
  }

  def notFoundRoute: Route = {
    extractUri { uri =>
      complete(StatusCodes.NotFound, s"document $uri not found")
    }
  }

  def getActorRef: ActorRef = parent.self

  def getActorRef (actorName: String): ActorRef = {
    val sel = parent.context.actorSelection(s"*/$actorName")
    val future: Future[ActorRef] = sel.resolveOne(1.second)
    Await.result(future,1.second) // this throws a ActorNotFound or TimedoutException if it does not succeed
  }

  def scheduleOnce (delay: FiniteDuration)(f: =>Unit): Cancellable = system.scheduler.scheduleOnce(delay)(f)

  //--- RouteInfo specific init/start/termination hooks

  def onRaceInitialized(server: HttpServer): Boolean = {
    _isHttps = server.useSSL
    true
  }

  // override in concrete types if we have to change modes etc
  def onRaceStarted(server: HttpServer): Boolean = {
    true
  }

  // override in concrete types if we have to do cleanup
  def onRaceTerminated(server: HttpServer): Boolean = {
    true
  }

  //--- document content fragment accululators (need to default to neutral element)

  // loaded *before* all RACE specific resources, e.g. for 3rd party code we use in own init
  def getPreambleHeaderFragments: Seq[Text.TypedTag[String]] = Seq.empty

  // RACE specific header parts
  def getHeaderFragments: Seq[Text.TypedTag[String]] = Seq.empty

  // loaded *after* all RACE specific resources
  def getPostambleHeaderFragments: Seq[Text.TypedTag[String]] = Seq.empty

  // loaded before all RACE specific body elements
  def getPreambleBodyFragments: Seq[Text.TypedTag[String]] = Seq.empty

  def getBodyFragments: Seq[Text.TypedTag[String]] = Seq.empty

  // loaded after all RACE specific resources
  def getPostambleBodyFragments: Seq[Text.TypedTag[String]] = Seq.empty

}

/**
  * a RaceRouteInfo that has an associated RaceRouteActor which sets the published content
  * from information received via RACE channel messages
  */
trait SubscribingRaceRoute extends RaceRouteInfo with RaceDataClient

/**
  * a RaceRouteInfo that has access to the simClock
  */
trait ContinuousTimeRaceRoute extends RaceRouteInfo {
  val simClock = RaceActorSystem(system).simClock

  def dateTime = simClock.dateTime
}


/**
 * a RaceRouteInfo that processes messages from associated DataClientRaceActors and re-routes them through its parent actor so
 * that we don't have to synchronize route resources
 */
trait DataClientRaceRoute extends RaceRouteInfo with RaceDataClient {

  override def receiveData: Receive = {
    case msg => parent.self ! RaceDataClientMessage(this, msg)
  }
}
