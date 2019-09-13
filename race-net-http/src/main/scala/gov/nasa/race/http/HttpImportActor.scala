/*
 * Copyright (c) 2016, United States Government, as represented by the
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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.concurrent.{Await, Future}

object HttpImportActor {
  var liveInstances: AtomicInteger = new AtomicInteger(0)

  def registerLive(a: HttpImportActor): Unit = {
    liveInstances.incrementAndGet
  }

  def unregisterLive(a: HttpImportActor): Boolean = {
    if (liveInstances.decrementAndGet == 0) {
      try {
        a.materializer.shutdown
        Await.ready(a.http.shutdownAllConnectionPools, 12.seconds)
      } catch {
        case _:java.util.concurrent.TimeoutException =>
          a.warning("shutting down Http pool timed out")
      }
    }
    true
  }
}

/**
  * import actor that publishes responses for configured, potentially periodic http requests
  */
class HttpImportActor (val config: Config) extends PublishingRaceActor
                                   with SubscribingRaceActor  with PeriodicRaceActor {
  import akka.pattern.pipe
  import context.dispatcher

  val loginRequest: Option[HttpRequest] = config.getOptionalConfig("login-request").flatMap(HttpRequestBuilder.get)
  var waitForLoginResponse = loginRequest.isDefined

  // domain -> reverse-path -> name -> value
  // can change with every response so we keep it mutable
  // safe to be mutable since it is only accessed from inside this actor
  val cookieMap: MMap[String, MMap[Path, MMap[String,Cookie]]] = MMap.empty

  var requests: Seq[HttpRequest] = config.getConfigSeq("data-requests").toList.flatMap(HttpRequestBuilder.get)

  //val pendingResponses = ArrayBuffer.empty[Future[HttpResponse]]

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  // if no explicit interval is set this is a one-time request
  override def defaultTickInterval: FiniteDuration = 0.seconds

  def sendRequest (request: HttpRequest) = {
    val req = addRequestCookies(request)
    info(s"sending http request to ${req.uri.authority}${req.uri.path}") // don't log credentials!
    val responseFuture = http.singleRequest(req)
    //pendingResponses += responseFuture
    responseFuture.pipeTo(self)
  }

  def addRequestCookies (request: HttpRequest): HttpRequest = {
    val cookies = getMatchingCookies(request.uri)
    if (cookies.nonEmpty) request.mapHeaders( hdrs => cookies ++ hdrs) else request
  }

  // find the longest matching path
  def getMatchingCookies (uri: Uri): List[Cookie] = {
    @tailrec def lookup (pathMap: MMap[Path, MMap[String,Cookie]], p: Path): List[Cookie] = {
      pathMap.get(p) match {
        case Some(cookies) => cookies.values.toList
        case None => if (p.isEmpty) List.empty else lookup(pathMap,p.tail)
      }
    }

    if (cookieMap.isEmpty) {
      List.empty  // no cookies at all

    } else {
      cookieMap.get(uri.authority.host.address) match {
        case Some(pathMap) =>
          if (pathMap.size == 1 && pathMap.contains(RootPath)){ // all-matcher, no need to iterate
            pathMap(RootPath).values.toList

          } else {
            // note we store paths in reverse so that we can iterate with tail
            lookup(pathMap, uri.path.reverse)
          }

        case None => List.empty // no cookies for this domain
      }
    }
  }

  override def handleMessage = {
    case RaceTick | BusEvent(_,SendHttpRequest,_) =>
      if (isLive) requests.foreach(sendRequest)

    case BusEvent(_,SendNewHttpRequest(request),_) =>
      sendRequest(request)

    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      headers.foreach { h =>
        h match {
          case `Set-Cookie`(cookie) =>
            for (
              domain <- cookie.domain;
              path <- cookie.path
            ) {
              val name = cookie.name
              val value = cookie.value
              val cookieHdr = Cookie(name,value)

              val domainMap = cookieMap.getOrElseUpdate(domain,MMap.empty)
              val pathMap = domainMap.getOrElseUpdate(Path(path).reverse, MMap.empty)
              pathMap += (name -> cookieHdr)
              info(f"adding cookie for $domain$path: $name=$value%20.20s..")
            }
          case other => debug(s"ignored http header: $other")
        }
      }

      if (waitForLoginResponse){
        waitForLoginResponse = false
        firstRequest

      } else {
        entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
          val msg = body.utf8String
          if (msg.nonEmpty) {
            info(f"received http response: $msg%20.20s..")
            publish(msg)
          }
        }
      }

    case resp @ HttpResponse(code, headers, entity, _) =>
      warning("Request failed, response code: " + code)

      if (log.isDebugEnabled) {
        entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
          debug(f"response: ${body.utf8String}%40.40s..")
        }
      } else {
        resp.discardEntityBytes()
      }
  }

  //--- system message callbacks
  override def onStartRaceActor(originator: ActorRef): Boolean = {
    ifTrue(super.onStartRaceActor(originator)) {
      HttpImportActor.registerLive(this)
      loginRequest match {
        case Some(req) =>
          waitForLoginResponse = true
          sendRequest(req)

        case None => firstRequest
      }
    }
  }

  def firstRequest = {
    if (tickInterval.length > 0) {
      if (requests.nonEmpty) startScheduler // otherwise there is no point scheduling
    } else {
      requests.foreach(sendRequest)
    }
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    stopScheduler
    HttpImportActor.unregisterLive(this) && super.onTerminateRaceActor(originator)
    //super.onTerminateRaceActor(originator) && HttpImportActor.unregisterLive(this)
  }
}
