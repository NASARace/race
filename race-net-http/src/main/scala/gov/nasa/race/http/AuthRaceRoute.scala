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

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.headers.{HttpCookie, RawHeader, SameSite}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, MediaTypes, StatusCode, StatusCodes, Uri, DateTime => DT}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.util.ByteString
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.NoConfig
import gov.nasa.race.http.webauthn.WebAuthnMethod
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.concurrent.duration._


/**
  * common parts of interactive and automatic RACE routes that require user authentication and authorization
  *
  * a request is only accepted if it includes a cookie that was transmitted in the previous response (cookie sequence)
  * this will make sure the cookie cannot be stolen without noticing, or stolen cookies loose validity quickly
  */
trait AuthRaceRoute extends RaceRouteInfo {

  //--- login/out paths
  val loginPath: String =  s"$requestPrefix/login"
  val logoutPath: String = s"$requestPrefix/logout"

  //--- path matchers (note that we do not match .html suffixes as neither login nor logout urls should be entered in the browser)
  val loginPathMatcher = PathMatchers.separateOnSlashes(loginPath)
  val logoutPathMatcher = PathMatchers.separateOnSlashes(logoutPath)

  val authPathMatcher = PathMatchers.separateOnSlashes(s"$requestPrefix/auth.html")
  val authSvgPathMatcher = PathMatchers.separateOnSlashes("auth.svg")  // it's a suffix matcher
  val authCssPathMatcher = PathMatchers.separateOnSlashes("auth.css")

  //--- session cookie management
  val sessionCookieName = config.getStringOrElse("cookie-name", "ARR")
  val cookieDomain: Option[String] = config.getOptionalString("cookie-domain")
  def cookiePath: Option[String] = Some(config.getStringOrElse("cookie-path","/"))

  val useStableCookie = config.getBooleanOrElse("use-stable-cookie", false)
  val expiresAfter = config.getFiniteDurationOrElse("expires-after", 10.minutes)
  val noCacheHeaders = Seq(
    RawHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0"),
    RawHeader("Pragma", "no-cache"),
    RawHeader("Expires","0")
  )
  // Http headers take precedence over meta tag - this enforces no client side caching and is required to disable the bfcache upon logout
  def noClientCache = config.getBooleanOrElse("no-client-cache", false)


  //--- sessions and user authentication method
  val sessions: SessionTokenStore = new SessionTokenStore(expiresAfter)
  val authMethod: AuthMethod = createAuthMethod()

  authMethod.setLogging(info,warning,error)

  override final def shouldUseHttps = true // depending on authMethod we might transmit user credentials

  def createAuthMethod(): AuthMethod = getConfigurableOrElse[AuthMethod]("auth")(new WebAuthnMethod(NoConfig))

  def cookieExpirationDate: Option[DT] = Some(DT.now + expiresAfter.toMillis)

  def isTokenIncrement (requestUri: Uri.Path): Boolean = !useStableCookie

  def targetUrl (requestUrl: String): String = {
    if (requestUrl.endsWith(loginPath)) requestPrefixUrl(requestUrl) else requestUrl
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
    cookie = cookie.withSameSite(SameSite.Strict)
    cookie
  }

  //--- login

  // override if we have to keep track of route specific session data for authorized users
  def addLoginUser (conn: SocketConnection, uid: String): Unit = {}
  def removeLoginUser (conn: SocketConnection, uid: String): Unit = {}

  def loginRoute: Route = {
    def httpEntity(contentType: ContentType, content: String) = HttpEntity.Strict(contentType, ByteString(content))

    path(loginPathMatcher) {
      post {  // this is a POST from the client that initiates the authMethod protocol
        entity(as[String]){ clientMsg =>
          headerValueByType(classOf[IncomingConnectionHeader]) { conn =>
            authMethod.processAuthMessage(conn, clientMsg) match {
              case Some(authResponse) =>
                authResponse match {
                  case AuthResponse.Accept(uid, msg, contentType) =>
                    addLoginUser(conn,uid)

                    val sessionToken = sessions.addNewEntry( conn.remoteAddress, uid)
                    val cookie = createSessionCookie(sessionToken)
                    setCookie(cookie) {
                      complete(StatusCodes.OK, httpEntity(contentType, msg))
                    }
                  case AuthResponse.Reject(msg, contentType) => complete(StatusCodes.Unauthorized, httpEntity(contentType, msg))
                  case AuthResponse.Challenge(msg, contentType) => complete(StatusCodes.Accepted, httpEntity(contentType, msg))
                }
              case None => // message not handled by authMethod
                warning(s"invalid auth request not handled: $clientMsg")
                complete(StatusCodes.Unauthorized, httpEntity(ContentTypes.`text/plain(UTF-8)`, "invalid authentication request"))
            }
          }
        }
      }
    }
  }

  def completeWithLogin (requestUrl: Uri): Route = {
    headerValueByType(classOf[IncomingConnectionHeader]) { conn =>
      respondWithHeaders(noCacheHeaders) {
        val remoteAddress = conn.remoteAddress
        complete(StatusCodes.OK, HttpEntity(ContentTypes.`text/html(UTF-8)`, loginPage(remoteAddress, requestUrl, loginPath)))
      }
    }
  }

  //--- logout

  /**
    * logout request - this indiscriminately terminates the session that is associated with the request cookie (if any)
    * and responds with a header that clears the cached/stored client data
    */
  def logoutRoute: Route = {
    path(logoutPathMatcher) {
      get {
        headerValueByType(classOf[IncomingConnectionHeader]) { conn =>
          optionalCookie(sessionCookieName) {
            case Some(namedCookie) => // there is a cookie in the request header
              // make sure we send a response header that deletes/invalidates the client side session token
              //setCookie( createSessionCookie("deleted", cookieDomain, cookiePath, Some(DT.MinValue))) {
              sessions.removeEntry(namedCookie.value) match {
                case Some(uid) => // it is a valid, un-expired session cookie - complete with a response header deleting it in the browser
                  removeLoginUser(conn, uid)
                  info(s"logout for '$uid' accepted")
                  respondWithHeader(RawHeader("Clear-Site-Data", "\"*\"")) {
                    // NOTE - unless the route was configured with 'no-client-cache = true' this does NOT clear the bfcache
                    complete(StatusCodes.OK, "user logged out")
                  }
                case None =>
                  respondWithHeader(RawHeader("Clear-Site-Data", "\"*\"")) {
                    complete(StatusCodes.BadRequest, "no active session") // wrong cookie - there is nothing to logout from
                  }
              }

            case None => // no cookie in the request header, nothing to log out from
              complete("user not logged in")
          }
        }
      }
    }
  }

  def logoutLink = a(href:=logoutPath.toString)("logout")


  //--- login resources

  // login content for document requests
  def loginPage (remoteAddress: InetSocketAddress, requestUrl: Uri, postUrl: String): String = authMethod.loginPage(remoteAddress,requestPrefix,requestUrl,postUrl)

  // content for "auth.html"
  def authPage(remoteAddress: InetSocketAddress, requestUrl: Uri, postUrl: String): String = authMethod.authPage(remoteAddress,requestPrefix,requestUrl,postUrl)

  def authPage (remoteAddress: InetSocketAddress): String = authMethod.authPage(remoteAddress)

  // content for "auth.css" (linked from "auth.html")
  def authCSS(): String = authMethod.authCSS()

  // content for "auth.svg" (used by "auth.html")
  def authSVG(): Array[Byte] = authMethod.authSVG()

  //--- routes and content completions

  override def completeRoute: Route = {
    loginRoute ~ authResourceRoute ~ logoutRoute ~ route
  }

  def authResourceRoute: Route = {
    path(authPathMatcher) {
      headerValueByType(classOf[IncomingConnectionHeader]) { conn =>
        val remoteAddress = conn.remoteAddress
        parameters("tgt"){ requestUrl =>
          complete(StatusCodes.OK, HttpEntity(ContentTypes.`text/html(UTF-8)`, authPage(remoteAddress, Uri(requestUrl), loginPath)))
        } ~ complete(StatusCodes.OK, HttpEntity(ContentTypes.`text/html(UTF-8)`, authPage(remoteAddress)))
      }
    } ~ path(authCssPathMatcher) {
      complete(StatusCodes.OK, HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), authCSS()))
    } ~ pathSuffix(authSvgPathMatcher) {
      complete( StatusCodes.OK, HttpEntity(MediaTypes.`image/svg+xml`, authSVG()))
    }
  }

  def getNextSessionToken (requestUri: Uri.Path, sessionToken: String): AuthTokenResult = {
    if (isTokenIncrement(requestUri)) {
      sessions.replaceExistingEntry(sessionToken)
    } else {
      sessions.matchesExistingEntry(sessionToken)
    }
  }

  /**
    * the main method to be used by implementors
    *
    * TODO - do we need to factor out routes that only support non-interactive user auth ?
    */
  def completeAuthorized(statusCode: StatusCode = StatusCodes.OK)(createContent: => HttpEntity.Strict): Route = {
    extractUri { uri =>
      extractMatchedPath { requestUrl =>
        cookie(sessionCookieName) { namedCookie =>
          getNextSessionToken(requestUrl, namedCookie.value) match {
            case NextToken(nextToken) =>
              setCookie(createSessionCookie(nextToken)) {
                completeWithCacheHeaders(statusCode, createContent)
              }
            case TokenMatched =>
              completeWithCacheHeaders(statusCode, createContent) // no need to set new cookie

            case f: TokenFailure => completeWithLogin(uri) // invalid session cookie, force new authentication
          }
        } ~ completeWithLogin(uri) // no session cookie yet, start authentication
      }
    }
  }

  /**
    * use this in lieu of complete() if we have to make sure the response is not cached on the client side
    * (http header takes precedence over meta tags - which are not even handled by some user clients)
    */
  def completeWithCacheHeaders[T](status: StatusCode, v: => T)(implicit m: ToEntityMarshaller[T]): Route = {
    if (noClientCache) {
      respondWithHeaders( noCacheHeaders) {
        complete(status,v)
      }
    } else {
      complete(status,v)
    }
  }
}
