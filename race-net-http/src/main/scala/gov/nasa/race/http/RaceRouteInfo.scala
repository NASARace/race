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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpCookie, `Set-Cookie`}
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.ClassUtils

import scalatags.Text.all.{span, head=>htmlHead, _}
import scalatags.Text.attrs.{name => nameAttr}

/**
  * base type for route infos, which consist of a akka.http Route and
  * an optional (child) RaceActor
  */
trait RaceRouteInfo {
  val config: Config
  def route: Route

  val name = config.getStringOrElse("name", getClass.getSimpleName)
  def internalRoute = route

  def shouldUseHttps = false
}

/**
  * mixin type for routes that require valid user credentials for routes that
  * end in a `completeAuthorized` directive
  *
  * NOTE: UserAuth objects are shared if they refer to the same password file, which
  * has to exist or instantiation of the route is throwing an exception
  */
trait AuthorizedRaceRoute extends RaceRouteInfo {
  // this will throw an exception if user-auth file does not exist
  val userAuth: UserAuth = UserAuth(new File(config.getVaultableStringOrElse("user-auth", ".passwd")))

  val loginPath = name + "-login"
  val logoutPath = name + "-logout"

  val cssPath = loginPath + ".css"
  val cssData = loginCSS
  val avatarPath = "login-users.svg"
  val avatarData = avatarImage

  val sessionCookieName = "oatmeal"

  override final def shouldUseHttps = true

  override def internalRoute = {
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
            userAuth.login(uid,pw.toCharArray) match {
              case Some(newToken) =>
                val response = html(
                  header(meta(httpEquiv := "refresh", content := s"0; url=$requestUri")),
                  body( "if not redirected automatically, follow ",
                    a(href:=requestUri)("this link to get back")
                  )
                )
                respondWithHeader(new `Set-Cookie`(new HttpCookie(sessionCookieName, newToken))) {
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

          validRequestResponse getOrElse {
            complete(StatusCodes.BadRequest, "invalid request")
          }
        }
      }
    }
  }

  def logoutRoute: Route = {
    path(logoutPath) {
      respondWithHeader(new `Set-Cookie`(new HttpCookie(sessionCookieName, "", Some(DateTime.now - 10)))) {
        completeLogout
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

  def completeAuthorized(requiredRole: String, m: => HttpEntity.Strict): Route = {
    extractMatchedPath { requestUri =>
      cookie(sessionCookieName) { namedCookie =>
        userAuth.crs.replaceExistingEntry(namedCookie.value, requiredRole) match {
          case Right(newToken) =>
            respondWithHeader(new `Set-Cookie`(new HttpCookie(sessionCookieName, newToken))) {
              complete(m)
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

  def completeLogout: Route = {
    cookie(sessionCookieName) { namedCookie =>
      if (userAuth.crs.removeEntry(namedCookie.value)) {
        complete("user logged out")
      } else {
        complete("no active session")
      }
    }
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