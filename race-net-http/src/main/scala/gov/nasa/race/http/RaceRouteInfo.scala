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
import java.net.InetSocketAddress

import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpCookie, `Set-Cookie`}
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentActor, SubscribingRaceActor}
import gov.nasa.race.util.ClassUtils
import scalatags.Text.all.{span, head => htmlHead, _}
import scalatags.Text.attrs.{name => nameAttr}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * base type for route infos, which consist of a akka.http Route and an optional RaceActor
  * to collect data used for the associated response content
  *
  * concrete RaceRouteInfo implementations have to provide a constructor that takes 2 args:
  *     (parent: ParentActor, config: Config)
  */
trait RaceRouteInfo {
  val parent: ParentActor
  val config: Config
  val name = config.getStringOrElse("name", getClass.getSimpleName)

  // this is the main function that defines the public (user) routes
  def route: Route

  // this can extend public routes with private ones used from within server responses  (such
  // as login routes used from within data request responses)
  def completeRoute: Route = route

  def shouldUseHttps = false

  def debug(f: => String) = gov.nasa.race.core.debug(f)(parent.log)
  def info(f: => String) = gov.nasa.race.core.info(f)(parent.log)
  def warning(f: => String) = gov.nasa.race.core.warning(f)(parent.log)
  def error(f: => String) = gov.nasa.race.core.error(f)(parent.log)
}

/**
  * common parts of interactive and automatic RACE routes that require user authorization
  *
  * we use a server-encrypted password store to keep a challenge-response token that is part
  * of each request (https get) - the request is only accepted if it includes a cookie that
  * was transmitted in the previous response
  */
trait AuthRaceRoute extends RaceRouteInfo {

  val sessionCookieName = config.getStringOrElse("cookie-name", "ARR")
  val cookieDomain = config.getOptionalString("cookie-domain")
  val cookiePath = config.getOptionalString("cookie-path")
  val expiresAfterMillis = config.getFiniteDurationOrElse("expires-after", 10.minutes).toMillis

  val loginPath = name + "-login"
  val logoutPath = name + "-logout"

  // this will throw an exception if user-auth file does not exist
  val userAuth: UserAuth = UserAuth(new File(config.getVaultableStringOrElse("user-auth", ".passwd")), expiresAfterMillis)


  override final def shouldUseHttps = true // we transmit passwords so this has to be encrypted

  //--- logout is not interactive

  def logoutRoute: Route = {
    path(logoutPath) {
      respondWithHeader(new `Set-Cookie`(new HttpCookie(sessionCookieName, "", Some(DateTime.now - 10)))) {
        optionalCookie(sessionCookieName) {
          case Some(namedCookie) =>
            userAuth.sessionTokens.removeEntry(namedCookie.value) match {
              case Some(user) =>
                info(s"logout for '${user.uid}' accepted")
                complete("user logged out")

              case None => completeWithFailure(StatusCodes.Forbidden, "no active session")
            }
          case None => completeWithFailure(StatusCodes.Forbidden, s"no user authorization for logout")
        }
      }
    }
  }

  def completeWithFailure (status: StatusCode, reason: String): Route = {
    warning(s"response failed: $reason ($status)")
    complete(status, reason)
  }

  protected def createSessionCookie (value: String): HttpCookie = {
    // default is we only set domain and path if they were configured, and otherwise leave
    // it to the client to choose them if missing (acknowledging that the server might be behind
    // a load balancing front end and hence not known to the client)
    // override if domain or path should be hardwired
    new HttpCookie(sessionCookieName, value, domain = cookieDomain, path = cookiePath, secure = true)
  }
}

/**
  * a RaceRouteInfo that assumes a {auto-login, data, ..., auto-logout} sequence without
  * manual interaction (hence no redirection or login/logout dialogs and resources)
  *
  * user authentication and session validation are the same as for manual auth
  */
trait AutoAuthorizedRaceRoute extends AuthRaceRoute {

  override def completeRoute = {
    route ~ loginRoute ~ logoutRoute
  }

  def completeAuthorized(requiredRole: String)(createResponseContent: => HttpEntity.Strict): Route = {
    extractMatchedPath { requestUri =>
      cookie(sessionCookieName) { namedCookie =>
        userAuth.nextSessionToken(namedCookie.value, requiredRole) match {
          case Right(newToken) =>
            respondWithHeader(new `Set-Cookie`(createSessionCookie(newToken))) {
              complete(createResponseContent)
            }
          case Left(rejection) =>
            complete(StatusCodes.Forbidden, s"invalid session token: $rejection")
        }
      } ~ complete(StatusCodes.Forbidden, "no user authorization found")
    }
  }

  /**
    * a login without retries or redirects
    */
  def loginRoute: Route = {
    post {
      path(loginPath) {
        entity(as[FormData]) { e =>
          val validRequestResponse = for (
            uid <- e.fields.get("u");
            pw <- e.fields.get("p")
          ) yield {
            if (userAuth.isLoggedIn(uid)) {
              warning(s"attempted login of '$uid' despite active session")
              complete(StatusCodes.Forbidden, "user is logged in")

            } else {
              userAuth.login(uid, pw.toCharArray) match {
                case Some(newToken) => // accept
                  respondWithHeader(new `Set-Cookie`(createSessionCookie(newToken))) {
                    info(s"login for '$uid' accepted")
                    complete(StatusCodes.OK, "user accepted")
                  }

                case None => // reject
                  complete(StatusCodes.Forbidden, "unknown user or wrong password")
              }
            }
          }

          validRequestResponse getOrElse {
            complete(StatusCodes.BadRequest, "invalid request")
          }
        }
      }
    }
  }
}

/**
  * mixin type for routes that require valid user credentials for routes that
  * end in a `completeAuthorized` directive
  *
  * NOTE: UserAuth objects are shared if they refer to the same password file, which
  * has to exist or instantiation of the route is throwing an exception
  */
trait AuthorizedRaceRoute extends AuthRaceRoute {

  //--- resources used in login dialog
  val cssPath = loginPath + ".css"
  val cssData = loginCSS
  val avatarPath = "login-users.svg"
  val avatarData = avatarImage


  override def completeRoute = {
    route ~ loginRoute ~ resourceRoute ~ logoutRoute
  }

  def loginRoute: Route = {
    post {
      path(loginPath) {
        entity(as[FormData]) { e =>
          val validRequestResponse = for (
            requestUri <- e.fields.get("r");
            uid <- e.fields.get("u");
            pw <- e.fields.get("p")
          ) yield {
            if (userAuth.isLoggedIn(uid)) {
              complete(StatusCodes.Forbidden, "user is logged in")

            } else {
              userAuth.login(uid, pw.toCharArray) match {
                case Some(newToken) =>
                  val response = html(
                    header(meta(httpEquiv := "refresh", content := s"0; url=$requestUri")),
                    body("if not redirected automatically, follow ",
                      a(href := requestUri)("this link to get back")
                    )
                  )
                  respondWithHeader(new `Set-Cookie`(createSessionCookie(newToken))) {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, response.render))
                  }

                case None => // unknown user or invalid pw
                  userAuth.remainingLoginAttempts(uid) match {
                    case -1 => completeLogin(requestUri, Some("unknown user id"))
                    case 0 => complete(StatusCodes.Forbidden, "user has exceeded login attempts")
                    case 1 => completeLogin(requestUri, Some(s"invalid password, ONLY 1 ATTEMPT LEFT!"))
                    case n => completeLogin(requestUri, Some(s"invalid password, $n attempts remaining"))
                  }
              }
            }
          }

          validRequestResponse getOrElse {
            complete(StatusCodes.BadRequest, "invalid request")
          }
        }
      }
    }
  }

  def resourceRoute: Route = {
    get {
      path(cssPath) {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`,HttpCharsets.`UTF-8`), cssData))
      } ~ path(avatarPath) {
        complete(HttpEntity(MediaTypes.`image/svg+xml`, avatarData))
      }
    }
  }

  def completeAuthorized(requiredRole: String)(createResponseContent: => HttpEntity.Strict): Route = {
    extractMatchedPath { requestUri =>
      cookie(sessionCookieName) { namedCookie =>
        userAuth.nextSessionToken(namedCookie.value, requiredRole) match {
          case Right(newToken) =>
            respondWithHeader(new `Set-Cookie`(new HttpCookie(sessionCookieName, newToken))) {
              complete(createResponseContent)
            }
          case Left(rejection) => completeLogin(requestUri.toString, Some(rejection))
        }
      } ~ completeLogin(requestUri.toString)
    }
  }

  def completeLogin(requestUri: String, alert: Option[String] = None): Route = {
    val page = loginPage(loginPath, requestUri, alert).render
    complete(StatusCodes.Unauthorized, HttpEntity(ContentTypes.`text/html(UTF-8)`, page))
  }



  //--- HTML artifacts

  def loginPage (postUri: String, requestUri: String, alert: Option[String]) = html(
    htmlHead(
      link(rel:="stylesheet", tpe:="text/css", href:=cssPath)
    ),
    body(onload:="document.getElementById('id01').style.display='block'")(
      p("you need to be logged in to access this page"),
      div(id:="id01",cls:="modal")(
        form(action:=postUri, cls:="modal-content animate")(
          span(cls:="close", title:="Close Modal",
            onclick:="document.getElementById('id01').style.display='none'")("×"),
          div(cls:="imgcontainer")(
            img(src:=avatarPath,alt:="Avatar",cls:="avatar")
          ),
          div(cls:="container")(
            alert match {
              case Some(msg) => p(span(cls:="alert")(msg))
              case None => ""
            },
            table(cls:="noBorder")(
              tr(
                td(cls:="labelCell")(b("User")),
                td(style:="width: 99%;")(
                  input(tpe:="text",nameAttr:="u",placeholder:="Enter Username",required:="true",autofocus:="true")
                )
              ),
              tr(
                td(cls:="labelCell")(b("Password")),
                td(
                  input(tpe:="password",nameAttr:="p",placeholder:="Enter Password",
                    required:="true",autocomplete:="on")
                )
              )
            ),
            input(tpe:="hidden", nameAttr:="r", value:=requestUri),
            button(tpe:="submit",formmethod:="post")("Login"),
            span(cls:="psw")(
              "Forgot ",
              a(href:="#")("password?")
            )
          )
        )
      )
    )
  )

  def logoutLink = a(href:=logoutPath)("logout")

  //--- resources

  def loginCSS: String = {
    ClassUtils.getResourceAsString(getClass,"login.css") match {
      case Some(cssText) => cssText
      case None => ""
    }
  }

  def avatarImage: Array[Byte] = {
    ClassUtils.getResourceAsBytes(getClass,"users.svg") match {
      case Some(imgData) => imgData
      case None => Array[Byte](0)
    }
  }
}

//--- basic RaceActor interfacing to obtain response data

/**
  * a RaceRouteInfo that has an associated RaceRouteActor which sets the published content
  * from information received via RACE channel messages
  */
trait SubscribingRaceRoute [T] extends RaceRouteInfo {

  protected val actorRef = createActor

  // BEWARE - can be called by the actor at any time during route evaluation, sync appropriately
  def setData (newData: T): Unit

  // to be provided by concrete type
  protected def instantiateActor: RaceRouteActor[T]

  protected def createActor: ActorRef = {
    val aRef = parent.actorOf(Props(instantiateActor), name)
    parent.addChildActorRef(aRef,config)
    aRef
  }
}

/**
  * actor base type that is associated with a SubscribingRaceRoute and produces the data that is
  * used as the response content
  *
  * implementations have to call setData(T) in response to receiving channel messages, i.e. from
  * somewhere within their handleMessage overrides
  */
trait RaceRouteActor[T] extends SubscribingRaceActor {
  val routeInfo: SubscribingRaceRoute[T] // to be set by ctor
}

//--- websocket interface to push from RaceActor

case class WebSocketConnection (queue: SourceQueueWithComplete[Message], remoteAddr: InetSocketAddress)

/**
  * a RaceRoute that completes with a WebSocket to which messages are pushed from an
  * associated actor that received data from RACE channels and turns them into web socket Messages
  */
trait PushWSRaceRoute extends RaceRouteInfo {

  val srcBufSize = config.getIntOrElse("source-queue", 16)
  val srcPolicy = config.getStringOrElse( "source-policy", "dropTail") match {
    case "dropHead" => OverflowStrategy.dropHead
    case "dropNew" => OverflowStrategy.dropNew
    case "dropTail" => OverflowStrategy.dropTail
    case "dropBuffer" => OverflowStrategy.dropBuffer
    case "fail" => OverflowStrategy.fail
    case other => throw new ConfigException.Generic(s"unsupported source buffer overflow strategy: $other")
  }

  //final implicit val materializer: Materializer = Materializer.matFromSystem(parent.context.system)
  final implicit val materializer: Materializer = Materializer.createMaterializer(parent.context)

  private var connections: List[WebSocketConnection] = List.empty

  protected val actorRef = createActor

  protected def instantiateActor: PushWSRaceRouteActor

  protected def createActor: ActorRef = {
    val aRef = parent.actorOf(Props(instantiateActor), name)
    parent.addChildActorRef(aRef,config)
    aRef
  }

  /**
    * called by associated actor
    * NOTE - this is executed from the actor thread and can modify connections so we have to synchronize
    */
  def push(m: Message): Unit = synchronized {
    implicit val ec = scala.concurrent.ExecutionContext.global

    for (conn <- connections) {
      conn.queue.offer(m).onComplete { res=>
        res match {
          case Success(_) => // all good (TODO should we check for Enqueued here?)
          case Failure(_) =>
            info(s"dropping connection: ${conn.remoteAddr}")
            connections = connections.filter( _ ne conn)
        }
      }
    }
  }

  /**
    * handle incoming message
    * override in subclasses if incoming messages need to be processed - default is doing nothing
    * note this executes in a synchronized context
    */
  protected def handleIncoming (m: Message): Unit = {
    info(s"ignoring incoming message $m")
  }

  /**
    * non-overridable wrappwr for user overridable handleIncoming(m)
    * this has to be synchronized since it can be called concurrently
    */
  private def processInbound (m: Message): Unit = {
    synchronized {
      handleIncoming(m)
    }
  }

  /**
    * complete routes that create web-sockets with this call
    * NOTE - this might execute overlapping with push(), hence we synchronize
    */
  protected def promoteToWebSocket: Route = {

    headerValueByType[RealRemoteAddress](()) { remoteAddrHdr =>
      val remoteAddress = remoteAddrHdr.address
      val inbound: Sink[Message,Any] = Sink.foreach(processInbound)
      val outbound: Source[Message,SourceQueueWithComplete[Message]] = Source.queue(srcBufSize,srcPolicy)

      val flow = Flow.fromSinkAndSourceCoupledMat(inbound, outbound)((_, outBoundMat) => synchronized {
          connections ::= WebSocketConnection(outBoundMat, remoteAddress)
          NotUsed
      })
      info(s"promoting to websocket connection: $remoteAddress")
      handleWebSocketMessages(flow)
    }
  }
}

/**
  * actor base type that is associated with a PushWSRaceRoute and pushes content it receives through RACE channels to
  * external websocket clients.
  *
  * Note this is one actor per PushWSRaceRoute instance, not per materialized route (=request). While we also could
  * do that it could result in gazillions of short-living, dynamically created/terminated SubscribingRaceActors, which
  * runs against our normal RaceActor convention
  */
trait PushWSRaceRouteActor extends SubscribingRaceActor {
  val route: PushWSRaceRoute // to be set by ctor

  protected def push (m: Message): Unit = {
    route.push(m)
  }
}