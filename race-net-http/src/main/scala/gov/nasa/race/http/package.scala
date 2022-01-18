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
package gov.nasa.race

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, HttpRequest, MediaTypes}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.HttpCookie
import scalatags.generic.TypedTag
import scalatags.text.{Builder => STBuilder}

/**
  * package gov.nasa.race.http contains actors to serve and retrieve http data
  */
package object http {
  type HtmlElement = TypedTag[STBuilder,String,String]
  type HtmlResources = Map[String,ToResponseMarshallable]

  final val RootPath = Path("/")

  case object SendHttpRequest
  case class SendNewHttpRequest(request: HttpRequest)

  // akka-http does not support public cookie-to-string rendering
  def renderToString(cookie: HttpCookie): String = {
    val sb = new StringBuilder

    sb.append(cookie.name()); sb.append('='); sb.append(cookie.value())
    cookie.expires.foreach( d=> { sb.append("; Expires="); sb.append(d.toRfc1123DateTimeString) })
    cookie.maxAge.foreach( d=> { sb.append("; Max-Age="); sb.append(d) })
    cookie.domain.foreach( d=> { sb.append("; Domain="); sb.append(d) })
    cookie.path.foreach( p=> { sb.append("; Path="); sb.append(p) })
    if (cookie.secure()) sb.append("; Secure")
    if (cookie.httpOnly()) sb.append("; HttpOnly")
    cookie.sameSite.foreach( p=> { sb.append("; SameSite="); sb.append(p) })

    sb.toString()
  }
}
