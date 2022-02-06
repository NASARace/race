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

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.http.webauthn.WebAuthnMethod

import java.io.File
import java.net.InetSocketAddress

/**
  * a route that enforces web socket promotion from within a user-authorized context
  *
  * use this authorization if the websocket is embedded in some other protected content, i.e. we have a cookie-based
  * per-request authentication and the websocket request comes from an already authenticated document
  *
  * note we only check but do not update the session token (there is no HttpResponse we could use to transmit
  * a new token value), and we provide an additional config option to specify a shorter expiration. This is based
  * on the model that the promotion request is automated (via script) from within a previous (authorized) response
  * content, i.e. the request should happen soon after we sent out that response
  *
  * note also that AuthRaceRoute does not check for session token expiration on logout requests, which in case
  * of responses that open web sockets would probably fail since those are usually long-running content used to
  * display pushed data
  */
trait AuthWSRaceRoute extends WSRaceRoute with AuthRaceRoute {

  override final protected def promoteToWebSocket (): Route = {
    def completeWithContext(requestUri: Uri.Path, ctx: AuthWSContext): Route = {
      val flow = createFlow(ctx)
      info(s"promoting $requestUri to websocket connection for remote: ${ctx.sockConn.remoteAddress}")

      val cookie = createSessionCookie(ctx.sessionToken)
      setCookie(cookie) {
        handleWebSocketMessages(flow)
      }
    }

    extractMatchedPath { requestUri =>
      headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
        cookie(sessionCookieName) { namedCookie =>
          val curToken = namedCookie.value
          getNextSessionToken(requestUri, curToken) match {
            case NextToken(nextToken) => completeWithContext( requestUri, AuthWSContext(sockConn,nextToken))
            case TokenMatched => completeWithContext( requestUri, AuthWSContext(sockConn,curToken))
            case f: TokenFailure => complete(StatusCodes.Forbidden, s"invalid session token: ${f.reason}")
          }
        } ~ complete(StatusCodes.Forbidden, "no user authorization")
      }
    }
  }

  /**
    * execute provided function with an AuthWSContext object.
    *
    * While this is not optimal since it includes a runtime type check it is safe since we create the flow (which
    * uses the context object) in our sealed promoteToWebSocket(). The alternative would be a type variable for
    * the WSContext object type used in the top-level initializeConnection(ctx) and handleIncoming(ctx) method
    * definitions, but that would force us to make this choice explicit in all (otherwise orthogonal) WSRaceRoute
    * types (Push/Protocol etc) and lead to type explosion.
    *
    * Both initializeConnection(ctx..) and handleIncoming(ctx..) can be chained over our linearized type hierarchy, and some
    * of these types might not need an AuthWSContext. In fact, restricting the scope in which the the session/user data
    * is visible is a good idea in its own right
    */
  protected def withAuthWSContext [T] (ctx: WSContext)(f: AuthWSContext=>T): T = {
    ctx match {
      case authCtx: AuthWSContext => f(authCtx)
      case _ => throw new RuntimeException("not an authorized WSContext")
    }
  }
}

/**
  * a route that supports authorized web socket promotion outside of other authorized content
  * This is typically the case if the client is automated
  *
  * use this authorization if the client directly requests the web socket and nothing else, i.e. we have only one
  * point of authentication, and the client uses akka-http BasicHttpCredentials (in extraHeaders)
  */
trait PwAuthorizedWSRoute extends AuthWSRaceRoute {

  val pwStore: PwUserStore = createPwStore

  def createPwStore: PwUserStore = {
    val fname = config.getVaultableString("user-auth")
    val file = new File(fname)
    if (file.isFile) {
      new PwUserStore(file)
    } else throw new RuntimeException("user-auth not found")
  }

  protected def promoteAuthorizedToWebSocket (requiredRole: String): Route = {
    extractCredentials { cred =>
      cred match {
        case Some(credentials) =>
          pwStore.getUserForCredentials( credentials) match {
            case Some(user) =>
              info(s"promoting to web socket for authorized user ${user.uid}")
              promoteToWebSocket()
            case None =>
              complete(StatusCodes.Forbidden, "unknown user credentials")
          }
        case None =>
          complete(StatusCodes.Forbidden, "no user credentials")
      }
    }
  }
}

/**
  * a route that can get promoted to a websocket connection which supports privileged operations, i.e. does
  * respond to certain incoming client messages by starting an authMethod based protocol
  *
  * use this for authorized WSRaceRoutes that can be used from within non-authorized documents
  *
  * note it is the responsibility of the concrete type to keep track of sessions
  *
  * TODO - do we want this to be an AuthWSRaceRoute ?
  */
trait AuthorizingWSRaceRoute extends WSRaceRoute {

  val authPathMatcher = PathMatchers.separateOnSlashes(s"$requestPrefix/auth.html")
  val authSvgPathMatcher = PathMatchers.separateOnSlashes("auth.svg")  // it's a suffix matcher
  val authCssPathMatcher = PathMatchers.separateOnSlashes("auth.css")

  val authMethod: AuthMethod = createAuthMethod()
  authMethod.setLogging(info,warning,error)

  def createAuthMethod(): AuthMethod = getConfigurableOrElse[AuthMethod]("auth")(new WebAuthnMethod(NoConfig))

  def authResourceRoute: Route = {
    path(authPathMatcher) {
      headerValueByType(classOf[IncomingConnectionHeader]) { conn =>
        val remoteAddress = conn.remoteAddress
        complete(StatusCodes.OK, HttpEntity(ContentTypes.`text/html(UTF-8)`, authPage(remoteAddress)))
      }
    } ~ pathSuffix(authCssPathMatcher) {
      complete(StatusCodes.OK, HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), authCSS()))
    } ~ pathSuffix(authSvgPathMatcher) {
      complete( StatusCodes.OK, HttpEntity(MediaTypes.`image/svg+xml`, authSVG()))
    }
  }

  def authPage (remoteAddress: InetSocketAddress): String = authMethod.authPage(remoteAddress)

  def authCSS(): String = authMethod.authCSS()

  def authSVG(): Array[Byte] = authMethod.authSVG()

  override def completeRoute: Route = {
    authResourceRoute ~ route
  }
}


