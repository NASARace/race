/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import akka.http.scaladsl.model.{ContentType, ContentTypes}
import gov.nasa.race.common.{ByteSlice, JsonPullParser, LogWriter, StringJsonPullParser}
import gov.nasa.race.util.ClassUtils
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress

sealed trait AuthResponse

object AuthResponse {
  case class Challenge (data: String, contentType: ContentType = ContentTypes.`application/json`) extends AuthResponse  // send msg back to client asking for more data
  case class Accept (uid: String, msg: String, contentType: ContentType = ContentTypes.`application/json`) extends AuthResponse  // user authenticated
  case class Reject (msg: String, contentType: ContentType = ContentTypes.`application/json`) extends AuthResponse  // user authentication terminally rejected
}

object AuthMethod {
  val defaultAuthCSS: String = {
    ClassUtils.getResourceAsString(getClass,"auth.css") match {
      case Some(cssText) => cssText
      case None => ""
    }
  }

  val defaultAuthSVG: Array[Byte] = {
    ClassUtils.getResourceAsBytes(getClass,"lock.svg") match {
      case Some(imgData) => imgData
      case None => Array[Byte](0)
    }
  }

  val defaultLoginPage: String = {
    ClassUtils.getResourceAsString(getClass,"login.html") match {
      case Some(html) => html
      case None => ""
    }
  }

  def scriptNode (code: String): Text.RawFrag = raw(s"""<script>$code</script>""")
}
import AuthMethod._

/**
  * abstraction for authentication methods that can use message protocols to communicate with the user client
  *
  * AuthMethods are only concerned about registration/authentication. Client communication method, session- and
  * user permission management is strictly the responsibility of the context/caller
  */
trait AuthMethod extends LogWriter {
  // the server side of the authentication protocol

  /**
    * this is called from AuthRaceRoute, i.e. once per GET
    * override if this is called so frequently that we want to cache the parser but be aware there can be concurrent calls
    */
  def processAuthMessage (conn: SocketConnection, clientMsg: String): Option[AuthResponse] = {
    val parser = new StringJsonPullParser
    if (parser.initialize(clientMsg)) {
      parser.ensureNextIsObjectStart()
      parser.readNext()
      processJSONAuthMessage(conn, parser.member, parser)
    } else None
  }

  /**
    * this is the method specific workhorse that implements the protocol, which is at least called for websocket
    * operation auth (but can also be used to implement the document auth - see above)
    *
    * note that the parser is guaranteed to be initialized and has already read the 1st member of the toplevel object
    * note also that we don't know here if we own the parser - it might be the parser for the incoming websocket
    * client messages
    */
  def processJSONAuthMessage (conn: SocketConnection, msgTag: ByteSlice, parser: JsonPullParser): Option[AuthResponse]

  def loginPage (remoteAddress: InetSocketAddress, requestUrl: String, postUrl: String): String = defaultLoginPage

  def authPage (remoteAddress: InetSocketAddress, requestUrl: String, postUrl: String): String // the document version

  def authPage (remoteAddress: InetSocketAddress): String // the websock version

  def authCSS(): String = defaultAuthCSS

  def authSVG(): Array[Byte] = defaultAuthSVG

  def startAuthMessage (uid: String): String = s"""{"startAuth":"$uid"}"""
  def rejectAuthMessage (reason: String): String = s"""{"reject":"$reason"}"""

  // override if this method needs to store credentials etc
  def shutdown(): Unit = {}
}
