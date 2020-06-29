/*
 * Copyright (c) 2020, United States Government, as represented by the
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
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.model.{ContentType, ContentTypes, FormData, HttpCharsets, HttpEntity, MediaTypes, StatusCode, StatusCodes, DateTime => DT}
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.{PathMatchers, Route}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.ClassUtils
import scalatags.Text.all.{span, head => htmlHead, _}
import scalatags.Text.attrs.{name => nameAttr}
import scala.concurrent.duration._


/**
  * common parts of interactive and automatic RACE routes that require user authorization
  *
  * we use a server-encrypted password store to keep a challenge-response token that is part
  * of each request (https get) - the request is only accepted if it includes a cookie that
  * was transmitted in the previous response
  */
trait AuthRaceRoute extends RaceRouteInfo {

  val loginPath: String =  s"$requestPrefix/login"
  val loginPathMatcher = PathMatchers.separateOnSlashes(loginPath)
  val logoutPath: String = s"$requestPrefix/logout"
  val logoutPathMatcher = PathMatchers.separateOnSlashes(logoutPath)

  // override if we have a more specific cookie parameters
  val sessionCookieName = config.getStringOrElse("cookie-name", "ARR")
  val cookieDomain: Option[String] = config.getOptionalString("cookie-domain")
  def cookiePath: Option[String] = Some(config.getStringOrElse("cookie-path","/"))

  val expiresAfterMillis = config.getFiniteDurationOrElse("expires-after", 10.minutes).toMillis
  def cookieExpirationDate: Option[DT] = Some(DT.now + expiresAfterMillis)

  // this will throw an exception if user-auth file does not exist
  val userAuth: UserAuth = UserAuth(new File(config.getVaultableStringOrElse("user-auth", ".passwd")), expiresAfterMillis)


  override final def shouldUseHttps = true // we transmit passwords so this has to be encrypted

  //--- logout is not interactive

  // TODO - should we check for expiration here? If so, what to do with web socket promotions?

  /**
    * this does not involve interactions and only succeeds if there is a valid session token
    */
  protected def authLogoutRoute: Route = {
    path(logoutPathMatcher) {
      optionalCookie(sessionCookieName) {
        case Some(namedCookie) => // request header has a session cookie - make sure we delete it in the response
          setCookie(createSessionCookie("deleted", cookieDomain, cookiePath, Some(DT.MinValue))) {
            userAuth.sessionTokens.removeEntry(namedCookie.value) match {
              case Some(user) =>
                info(s"logout for '${user.uid}' accepted")
                complete("user logged out")

              case None => completeWithFailure(StatusCodes.OK, "no active session") // there is nothing to logout from
            }
          }
        case None => completeWithFailure(StatusCodes.Forbidden, s"no user authorization for logout")
      }
    }
  }

  def logoutRoute: Route = authLogoutRoute

  def completeWithFailure (status: StatusCode, reason: String): Route = {
    warning(s"response failed: $reason ($status)")
    complete(status, reason)
  }

  /**
    * override if domain, path and expiration should be route type specific
    */
  protected def createSessionCookie (cookieValue: String,
                                     dom: Option[String] = cookieDomain,
                                     pth: Option[String] = cookiePath,
                                     exp: Option[DT] = cookieExpirationDate): HttpCookie = {
    var cookie = HttpCookie(sessionCookieName,cookieValue)
    dom.foreach( ds=> cookie = cookie.withDomain(ds))
    pth.foreach( ps=> cookie = cookie.withPath(ps))
    exp.foreach( dt=> cookie = cookie.withExpires(dt))
    cookie
  }
}

/**
  * a RaceRouteInfo that assumes a {auto-login, data, ..., auto-logout} sequence without
  * manual interaction (hence no redirection or login/logout dialogs and resources)
  *
  * user authentication and session validation are the same as for manual auth
  */
trait PreAuthorizedRaceRoute extends AuthRaceRoute {

  override def completeRoute = {
    // no need for resources since there are no interactive web pages
    loginRoute ~ logoutRoute ~ route
  }

  def completeAuthorized(requiredRole: String)(createResponseContent: => HttpEntity.Strict): Route = {
    extractMatchedPath { requestUri =>
      cookie(sessionCookieName) { namedCookie =>
        userAuth.nextSessionToken(namedCookie.value, requiredRole) match {
          case NextToken(newToken) =>
            setCookie(createSessionCookie(newToken)) {
              complete(createResponseContent)
            }
          case TokenFailure(rejection) =>
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
      path(loginPathMatcher) {
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
                  setCookie(createSessionCookie(newToken)) {
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
  val cssPathMatcher = PathMatchers.separateOnSlashes(s"$requestPrefix/auth-style.css")
  val cssData = loginCSS
  val avatarPathMatcher = PathMatchers.separateOnSlashes(s"$requestPrefix/auth-avatar.svg")
  val avatarData = avatarImage

  override def completeRoute: Route = {
    loginRoute ~ authResourceRoute ~ logoutRoute ~ route
  }

  def loginRoute: Route = {
    path(loginPathMatcher) {
      post {
        formFields("r") { requestUri =>
          formFields("u") { uid =>
            if (userAuth.isLoggedIn(uid)) {
              complete(StatusCodes.Forbidden, "user is already logged in")
            } else {
              formFields("p") { pw =>
                userAuth.login(uid, pw.toCharArray) match {
                  case Some(newToken) =>
                    val response = if (requestUri.nonEmpty) {
                      html(
                        header(meta(httpEquiv := "refresh", content := s"0; url=$requestUri")),
                        body("if not redirected automatically, follow ",
                          a(href := requestUri)("this link to get back")
                        )
                      )
                    } else {
                      html(
                        body("login successful")
                      )
                    }
                    info(s"user '$uid' logged in")
                    setCookie(createSessionCookie(newToken)) {
                      complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, response.render))
                    }

                  case None => // unknown user or invalid pw
                    info(s"user '$uid' login failed'")
                    val req = if (requestUri.isEmpty) None else Some(requestUri)
                    userAuth.remainingLoginAttempts(uid) match {
                      case -1 => completeLogin(req, Some("unknown user id"))
                      case 0 => complete(StatusCodes.Forbidden, "user has exceeded login attempts")
                      case 1 => completeLogin(req, Some(s"invalid password, ONLY 1 ATTEMPT LEFT!"))
                      case n => completeLogin(req, Some(s"invalid password, $n attempts remaining"))
                    }
                }
              }
            }
          }
        } ~ { // incomplete or wrong form data
          complete(StatusCodes.BadRequest, "invalid login data")
        }
      } ~ get { // explicit login page request (GET)
        optionalCookie(sessionCookieName) {
          case Some(namedCookie) =>
            userAuth.matchesSessionToken(namedCookie.value, User.UserRole) match {
              case TokenMatched => complete(StatusCodes.Forbidden, "already logged in")
              case TokenFailure(rejection) => completeLogin(None, Some(rejection))
            }
          case None => completeLogin(None)
        }
      }
    }
  }

  def authResourceRoute: Route = {
    get {  // login page resources
      path(cssPathMatcher) {
        complete(HttpEntity(ContentType(MediaTypes.`text/css`,HttpCharsets.`UTF-8`), cssData))
      } ~ path(avatarPathMatcher) {
        complete(HttpEntity(MediaTypes.`image/svg+xml`, avatarData))
      }
    }
  }

  /**
    * logout request that can be interactively authorized by user credentials if the client does not have a valid session token
    *
    * can be used to enforce logout from different sessions/browsers without having to manually delete cookies
    * in the browser
    */
  override def logoutRoute: Route = {
    path(logoutPathMatcher) {
      get {
        optionalCookie(sessionCookieName) {
          case Some(namedCookie) => // there is a cookie in the request header
            // make sure we send a response header that deletes/invalidates the client side session token
            setCookie(createSessionCookie("deleted", cookieDomain, cookiePath, Some(DT.MinValue))) {
              userAuth.removeEntry(namedCookie.value) match {
                case Some(user) => // it is a valid, un-expired session cookie - complete with a response header deleting it in the browser
                  info(s"logout for '${user.uid}' accepted")
                  complete("user logged out")
                case None => complete(StatusCodes.OK, "no active session") // there is nothing to logout from
              }
            }

          case None => // no cookie in the request header, go interacive to find out who is supposed to be logged out
            extractMatchedPath { requestUri =>
              completeLogout(Some(requestUri.toString), Some("authorize logout request"))
            }
        }

      } ~ post { // interactive logout form response
        entity(as[FormData]) { e =>
          val validRequestResponse = for (
            uid <- e.fields.get("u");
            pw <- e.fields.get("p")
          ) yield {
            userAuth.authenticate(uid,pw.toCharArray) match {
              case Some(user) =>
                userAuth.sessionTokens.removeUser(user)
                info(s"user $uid logged out")
                complete(StatusCodes.OK, "user logged out")
              case None =>
                completeLogout(None,Some("invalid user credentials"))
            }
          }

          validRequestResponse getOrElse {
            complete(StatusCodes.BadRequest, "invalid request")
          }
        }
      }
    }
  }

  def completeAuthorized(requiredRole: String, statusCode: StatusCode = StatusCodes.OK)(createContent: => HttpEntity.Strict): Route = {
    extractMatchedPath { requestUri =>
      cookie(sessionCookieName) { namedCookie =>
        userAuth.nextSessionToken(namedCookie.value, requiredRole) match {
          case NextToken(newToken) =>
            setCookie(createSessionCookie(newToken)) {
              complete( statusCode, createContent)
            }
          case TokenFailure(rejection) => completeLogin(Some(requestUri.toString), Some(rejection))
        }
      } ~ completeLogin(Some(requestUri.toString))
    }
  }

  def completeLogin(requestUri: Option[String], alert: Option[String] = None): Route = {
    val page = loginPage(loginPath, requestUri, alert).render
    complete(StatusCodes.Unauthorized, HttpEntity(ContentTypes.`text/html(UTF-8)`, page))
  }

  def completeLogout(requestUri: Option[String], alert: Option[String] = None): Route = {
    val page = logoutPage(logoutPath, requestUri, alert).render
    complete(StatusCodes.Unauthorized, HttpEntity(ContentTypes.`text/html(UTF-8)`, page))
  }


  //--- HTML artifacts

  def authPage (authOp: String, pageText: String, postUri: String, requestUri: Option[String], alert: Option[String]) = html(
    htmlHead(
      link(rel:="stylesheet", tpe:="text/css", href:=s"/$requestPrefix/auth-style.css")
    ),
    body(onload:="document.getElementById('id01').style.display='block'")(
      p(pageText),
      div(id:="id01",cls:="modal")(
        form(action:=s"/$postUri", cls:="modal-content animate")(
          span(cls:="close", title:="Close Modal",
            onclick:="document.getElementById('id01').style.display='none'")("×"),
          div(cls:="imgcontainer")(
            img(src:=s"/$requestPrefix/auth-avatar.svg", alt:="Avatar", cls:="avatar")
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
                  input(tpe:="text",nameAttr:="u",placeholder:="Enter Username",required:=true,autofocus:=true)
                )
              ),
              tr(
                td(cls:="labelCell")(b("Password")),
                td(
                  input(tpe:="password",nameAttr:="p",placeholder:="Enter Password",
                    required:=true,autocomplete:="on")
                )
              )
            ),
            input(tpe:="hidden", nameAttr:="r", value:=requestUri.getOrElse("")),
            button(tpe:="submit",formmethod:="post")(authOp),
            span(cls:="psw")(
              "Forgot ",
              a(href:="#")("password?")
            )
          )
        )
      )
    )
  )

  def loginPage (postUri: String, requestUri: Option[String], alert: Option[String]) =
    authPage("Login", "you need to be logged in to access this page", postUri, requestUri, alert)


  def logoutPage (postUri: String, requestUri: Option[String], alert: Option[String]) =
    authPage("Logout", "you need to authenticate to log out", postUri, requestUri, alert)

  def logoutLink = a(href:=logoutPath.toString)("logout")

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

