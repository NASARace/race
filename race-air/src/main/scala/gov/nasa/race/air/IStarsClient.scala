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
package gov.nasa.race.air

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.stream.Materializer
import akka.util.ByteString
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{RaceActor, RaceActorSystem, _}

import scala.collection.immutable.Queue

/**
  * the singleton system actor that does the communication with the iSTARS server. Note this
  * needs to be a single instance so that we can rely on the order of responses
  * The sole state of this actor is the queue of pending requests, which effectively represents
  * the server state and hence we do not turn this into a RaceActor that could be reset etc.
  *
  * This actorRef is only used point-to-point from IStarsClients
  *
  * See http://www.icao.int/safety/iStars/Pages/API-Data-Service.aspx for available APIs
  */
class IStarRequester extends Actor {
  import IStarsClient._
  import context.dispatcher

  final implicit val materializer: Materializer = Materializer.matFromSystem(context.system)
  val http = Http(context.system)

  val ras = RaceActorSystem(context.system)
  implicit val log = ras.log
  private val url = ras.config.getVaultStringOrElse("istars.url", "https://v4p4sz5ijk.execute-api.us-east-1.amazonaws.com")
  private val key = ras.config.getVaultString("istars.key")

  // since (without pipelining) http uses the same TCP session for all communications with this host,
  // we get responses in order, which means we can keep responseActions in a simple queue as long
  // as it is thread safe (there can be more than one IStarsClient at a time)
  private var pendingRequests: Queue[Request] = Queue.empty

  override def receive: Receive = {
    //--- the concrete queries
    case request@LocationIndicatorRequest(requester,responseAction,icaoCode) =>
      pendingRequests = pendingRequests :+ request
      sendRequest(s"anbdata/airports/locations/doc7910?api_key=$key&airports=$icaoCode&format=json")

    //... and more to follow

    case response@HttpResponse(StatusCodes.OK, headers, entity, _) =>
      pendingRequests.headOption match {
        case Some(request) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
            request.sender ! Response(request.responseAction,body.utf8String)
          }
          pendingRequests = pendingRequests.tail
        case None =>
          warning("orphan response")
          response.discardEntityBytes()
      }

    case response @ HttpResponse(code, headers, entity, _) =>
      warning("failed response")
      response.discardEntityBytes()
      pendingRequests = pendingRequests.tail
  }

  private def sendRequest (query: String) = {
    import akka.pattern.pipe
    import context.dispatcher

    val req = HttpRequest(uri=s"$url/$query")
    http.singleRequest(req).pipeTo(self)
  }
}

/**
  * the shared (system) actor which does the communication with the iSTARS server.
  */
object IStarsClient {
  type ResponseAction = String=>Unit

  trait Request {
    val sender: ActorRef
    val responseAction: ResponseAction
  }
  case class LocationIndicatorRequest (sender: ActorRef,
                                       responseAction: ResponseAction,
                                       icaoCode: String) extends Request
  //... and more to follow

  case class Response (responseAction: ResponseAction, response: String) // generic response forwarding

  private var actorRef = ActorRef.noSender

  def initialize(system: ActorSystem) = {
    if (actorRef == ActorRef.noSender) {
      actorRef = system.actorOf(Props(classOf[IStarRequester]), "istarRequester")
    }
  }
}

/**
  * actor trait to queries data from ICAOs iSTARS data service
  */
trait IStarsClient extends RaceActor {
  import IStarsClient._

  initialize(system)

  //--- the APIs
  def airportLocationRequest (icaoCode: String)(responseAction: ResponseAction) = {
    actorRef ! LocationIndicatorRequest(self,responseAction,icaoCode)
  }
  //... and more to follow

  // this needs to be mixed in into the concrete class handleMessage like this:
  //    def handleMessage = { case ... } orElse handleIStarsResponse
  // or by delegating to super:
  //    def handleMessage = { case ... } orElse super.handleMessage

  def handleIStarsResponse: Receive = {
    case Response(responseAction: ResponseAction, response: String) => responseAction(response)
  }

  override def handleMessage = handleIStarsResponse orElse super.handleMessage
}

/**
  * a test client for iSTARS connectivity
  */
class TestIStarsClient (val config: Config) extends IStarsClient {
  val icaoCode = config.getStringOrElse("icao-code", "KSFO")

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    ifTrue(super.onStartRaceActor(originator)){
      println(s"requesting airportLocation for $icaoCode from iSTARS")
      airportLocationRequest(icaoCode){ response =>
        // note this gets executed once we got a response, NOT sync
        println(s"iSTARS response: $response")
      }
    }
  }
}