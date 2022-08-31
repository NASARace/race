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
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.Materializer
import akka.util.ByteString
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, RaceTick}
import gov.nasa.race.core.{PeriodicRaceActor, PublishingRaceActor, RaceContext, SubscribingRaceActor}
import gov.nasa.race.util.{NetUtils, StringUtils}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

object HttpImportActor {
  var liveInstances: AtomicInteger = new AtomicInteger(0)

  def registerLive(a: HttpImportActor): Unit = {
    liveInstances.incrementAndGet
  }

  def unregisterLive(a: HttpImportActor): Boolean = {
    if (liveInstances.decrementAndGet == 0) {
      try {
        //a.materializer.shutdown
        Await.ready(a.http.shutdownAllConnectionPools(), 10.seconds)
      } catch {
        case _:java.util.concurrent.TimeoutException =>
          a.warning("shutting down Http pool timed out")
      }
    }
    true
  }
}

/**
  * utility construct to keep track of requests and associated futures
  */
case class PendingRequest (id: Int, request: HttpRequest, responseFuture: Future[HttpResponse]) {
  override def toString: String = s"${request.method} ${request.uri}"
}

/**
  * utility construct to store cookies received in responses
  */
case class SetCookie (domain: String, path: String, name: String, private var _value: String) {
  private var _cookieHdr: Cookie = Cookie(name,_value)

  def cookieHdr = _cookieHdr

  def value = _value
  def value_= (newVal: String): Unit = {
    _value = newVal
    _cookieHdr = Cookie(name,newVal)
  }

  /**
    * this is used to find out if we have to add an existing SetCookie or just update its value
    * There is no domain or path expansion check - only cookies that had the same selectors are updated
    */
  @inline def isSame (cookieDomain: String, cookiePath: String, cookieName: String): Boolean = {
    (domain == cookieDomain) && (path == cookiePath) && (name == cookieName)
  }

  /**
    * this is used to check if a SetCookie should be sent with a request to a given uri
    */
  def isMatching (uri: Uri): Boolean = {
    val reqHost = uri.authority.host.address
    val reqPath = uri.path.toString

    NetUtils.isHostInDomain(reqHost,domain) && NetUtils.isPathInParent(reqPath,path)
  }
}

/**
  * import actor that publishes responses for configured, potentially periodic http requests
  *
  * This actor is supposed to be used for a single site so that we can have (optional) login/logout
  * requests and also keep track of which cookies to include in requests
  */
class HttpImportActor (val config: Config) extends PublishingRaceActor
                                   with SubscribingRaceActor  with PeriodicRaceActor {
  import akka.pattern.pipe
  import context.dispatcher

  val responseTimeout: FiniteDuration = config.getFiniteDurationOrElse("response-timeout", 5.seconds)

  val loginRequest: Option[HttpRequest] = config.getOptionalConfig("login-request").flatMap(HttpRequestBuilder.get)
  val logoutRequest: Option[HttpRequest] = config.getOptionalConfig("logout-request").flatMap(HttpRequestBuilder.get)

  var waitForLoginResponse = loginRequest.isDefined
  var nRequests: Int = 0

  // populated by 'Set-Cookie' header entries in responses
  val setCookies: ArrayBuffer[SetCookie] = ArrayBuffer.empty

  // do we allow several pending requests
  val coalesce: Boolean = config.getBooleanOrElse("coalesce", true)

  // the configured requests
  var requests: Seq[HttpRequest] = Seq.empty[HttpRequest] // initialized during RaceInitialize

  // do we publish response data as String or Array[Byte]?
  val publishAsBytes: Boolean = config.getBooleanOrElse("publish-raw", false)

  // we keep a queue of pending requests so that we can detect out-of-order responses and do graceful termination
  val pendingRequests = mutable.Queue.empty[PendingRequest]

  final implicit val materializer: Materializer = Materializer.matFromSystem(context.system)
  val http = Http(context.system)

  val clientSettings = {
    val origSettings = ClientConnectionSettings(system.settings.config)
    ConnectionPoolSettings(system.settings.config).withConnectionSettings(origSettings.withIdleTimeout(5.seconds))
  }

  // this is called during onInitializeRaceActor - override for hardwired requests
  // (the conf parameter might be a remote config)
  protected def createRequests (conf: Config): Seq[HttpRequest] = {
    config.getConfigSeq("data-requests").toList.flatMap(HttpRequestBuilder.get)
  }

  // if no explicit interval is set this is a one-time request
  override def defaultTickInterval: FiniteDuration = 0.seconds

  def sendReq (request: HttpRequest): PendingRequest = {
    val req = addRequestCookies(request)
    info(s"sending http request to ${req.uri}") // don't log credentials!
    val responseFuture: Future[HttpResponse] = http.singleRequest(req, settings = clientSettings)
    nRequests += 1
    PendingRequest(nRequests,req,responseFuture)
  }

  def sendRequest (request: HttpRequest) = {
    val pr = sendReq(request)
    pendingRequests += pr
    pr.responseFuture.map{ resp=>
      pendingRequests -= pr
      (pr,resp)
    }.pipeTo(self)
  }

  def sendRequests = {
    if (isLive) {
      if (pendingRequests.isEmpty || !coalesce) {
        requests.foreach(sendRequest)
      }
    }
  }

  def updateSetCookies (cookie: HttpCookie, req: PendingRequest): Unit = {
    val domain = cookie.domain.getOrElse(req.request.uri.authority.host.toString)
    val path = cookie.path.getOrElse("/")
    val name = cookie.name
    val value = cookie.value

    var i=0
    while (i < setCookies.length) {
      val sc = setCookies(i)
      if (sc.isSame(domain,path,name)) { // existing one - just update the value
        sc.value = value
        info(f"updated cookie value $domain:$path:$name =  $value%20.20s..")
        return
      }
      i += 1
    }

    info(f"added cookie value $domain:$path:$name =  $value%20.20s..")
    setCookies += SetCookie(domain,path,name,value)
  }


  def addRequestCookies (request: HttpRequest): HttpRequest = {
    var reqWithCookies = request
    val uri = request.uri

    setCookies.foreach { sc=>
      if (sc.isMatching(uri)) {
        info(f"adding request cookie ${sc.domain}:${sc.path}:${sc.name} =  ${sc.value}%20.20s..")
        reqWithCookies = reqWithCookies.withHeaders(sc.cookieHdr)
      }
    }

    reqWithCookies
  }

  override def onRaceTick(): Unit = sendRequests

  override def handleMessage: Receive = {
    case BusEvent(_,SendHttpRequest,_) => sendRequests

    case BusEvent(_,SendNewHttpRequest(request),_) =>
      if (isLive) sendRequest(request)

    case (pr:PendingRequest, resp:HttpResponse) =>
      processResponse(pr, resp)

    case Failure(reason) =>
      info(s"request failed for reason: $reason") // should this be a warning?
  }

  protected def processResponse (req: PendingRequest, resp: HttpResponse): Unit = {
    if (resp.status == StatusCodes.OK) {
      if (isLive) {
        resp.headers.foreach { h =>
          h match {
            case `Set-Cookie`(cookie) => updateSetCookies(cookie,req)
            case other => debug(s"ignored http header: $other")
          }
        }

        if (waitForLoginResponse && isLive) {
          waitForLoginResponse = false
          scheduleRequests

        } else {
          resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { processResponseData }
        }

      } else { // not alive, discard
        resp.discardEntityBytes()
      }

    } else { // request failed
      warning("Request failed, response code: " + resp.status)
      if (log.isDebugEnabled) {
        resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
          debug(f"response: ${body.utf8String}%40.40s..")
        }
      } else {
        resp.discardEntityBytes()
      }
    }
  }

  /**
    * this is the main extension point for publishing
    * override to translate the body data synchronously, which can also avoid copying the content
    */
  protected def processResponseData(body: ByteString): Unit = {
    if (publishAsBytes) {
      val msg = body.toArray
      if (msg.nonEmpty) {
        info(s"received http response: ${StringUtils.mkHexByteString(msg,20)}")
        publish(msg)
      }
    } else {
      val msg = body.utf8String
      if (msg.nonEmpty) {
        info(f"received http response: $msg%20.20s..")
        publish(msg)
      }
    }
  }

  //--- system message callbacks

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    requests = createRequests(actorConf)
    if (requests.nonEmpty) {
      super.onInitializeRaceActor(rc,actorConf)
    } else {
      error("no requests specified")
      false
    }
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    ifTrue(super.onStartRaceActor(originator)) {
      HttpImportActor.registerLive(this)
      loginRequest match {
        case Some(req) =>
          waitForLoginResponse = true
          sendRequest(req)

        case None => scheduleRequests
      }
    }
  }

  def scheduleRequests = {
    if (tickInterval.length > 0) {
      if (requests.nonEmpty) startScheduler // otherwise there is no point scheduling
    } else {
      requests.foreach(sendRequest) // send data request(s) once
    }
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {

    def waitForPending (pr: PendingRequest): Unit = {
      info(s"waiting for pending request $pr")

      waitForCompletion(pr.responseFuture, responseTimeout) {
        warning(s"pending requests did not complete in $responseTimeout, discarding")
        pr.request.discardEntityBytes(materializer)
        pr.responseFuture.onComplete{ _.get.discardEntityBytes() }
      }
    }

    stopScheduler // no more new data requests

    //--- finish/drop pending requests
    pendingRequests.foreach(waitForPending)
    pendingRequests.clear()

    // logout is different - make sure it is processed here because we might not come back to handleMessage
    ifSome(logoutRequest) { r =>
      val pr = sendReq(r)
      pr.responseFuture.onComplete { tr =>
        if (tr.isSuccess) processResponse(pr, tr.get)
      }
      waitForPending(pr)
    }

    HttpImportActor.unregisterLive(this)
    super.onTerminateRaceActor(originator)
  }

  def waitForCompletion(reqFuture: Future[HttpResponse], to: FiniteDuration)(failAction: =>Unit): Unit = {
    try {
      Await.ready(reqFuture, to) // give akka some time to complete
    } catch {
      case x: TimeoutException => // nothing we can do
        failAction
    }
  }
}
