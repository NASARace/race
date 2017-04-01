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

import gov.nasa.race.config.ConfigUtils._
import akka.http.scaladsl.model.headers.{HttpCookie, `Set-Cookie`}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{entity, extractUnmatchedPath, _}
import akka.http.scaladsl.server.{Directive0, Route}
import com.typesafe.config.Config
import gov.nasa.race.util.{ClassUtils, StringUtils}

import scalatags.Text.all.{span, _}
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
  val avatarPath = "login-users.svg"
  val avatarData = avatarImage

  override def internalRoute = {
    route ~ loginRoute ~ resourceRoute
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
                respondWithHeader(new `Set-Cookie`(new HttpCookie("oatmeal", newToken))) {
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

  def resourceRoute: Route = {
    get {
      path(avatarPath) {
        println(s"@@ sending avatar image: ${avatarData.length}")
        complete(HttpEntity(MediaTypes.`image/svg+xml`, avatarData))
      }
    }
  }

  def completeAuthorized(requiredRole: String, m: => HttpEntity.Strict): Route = {
    cookie("oatmeal") { namedCookie =>
      userAuth.crs.replaceExistingEntry(namedCookie.value,requiredRole) match {
        case Some(newToken) =>
          respondWithHeader(new `Set-Cookie`(new HttpCookie("oatmeal",newToken))) {
            complete(m)
          }

        case None =>
          complete(StatusCodes.Forbidden, "You are out of your depth!")
      }
    } ~ extractMatchedPath { requestUri => // show the login dialog
      completeLogin(requestUri.toString)
    }
  }

  def completeLogin(requestUri: String, alert: Option[String] = None): Route = {
    val page = loginPage(loginPath, requestUri, alert).render
    complete(StatusCodes.Unauthorized, HttpEntity(ContentTypes.`text/html(UTF-8)`, page))
  }


  // this returns a HTML page with a form for user authentication, i.e. we get this back
  // as a POST with a "u=⟨uid⟩&p=⟨password⟩" body
  def loginPage (postUri: String, requestUri: String, alert: Option[String]) = html(
    raw("<style>"),
    raw(loginCSS),
    raw("</style>"),
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
            label(b("User")),
            input(tpe:="text",nameAttr:="u",placeholder:="Enter Username",required:="true", autofocus:="true"),
            label(b("Password")),
            input(tpe:="password",nameAttr:="p",placeholder:="Enter Password",required:="true"),
            input(tpe:="hidden", nameAttr:="r", value:=requestUri),
            button(tpe:="submit",formmethod:="post")("Login"),
            input(tpe:="checkbox",checked:="checked")("remember me"),
            span(cls:="psw")(
              "Forgot ",
              a(href:="#")("password?")
            )
          )
        )
      )
    )
  )

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