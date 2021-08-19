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

import akka.http.scaladsl.model.Uri
import gov.nasa.race.common.ConstUtf8Slice.utf8
import gov.nasa.race.common.{ByteSlice, JsonPullParser, StringJsonPullParser}
import gov.nasa.race.http.AuthMethod.scriptNode
import scalatags.Text.all._

import java.net.InetSocketAddress


/**
  * an AuthMethod that always passes
  *
  * USE ONLY FOR TESTING OR DEMOS
  */
class NoAuthMethod extends StringJsonPullParser with AuthMethod {
  val AUTH_USER = utf8("authUser")
  val AuthUserRE = """ *\{ *"authUser": *"(.*)" *\}""".r

  override def processAuthMessage (conn: SocketConnection, clientMsg: String): Option[AuthResponse] = {
    clientMsg match {
      case AuthUserRE(uid) =>
        Some(AuthResponse.Accept( uid, s"""{"accept":"$uid"}"""))
      case _ =>
        warning(s"ignoring malformed user authentication: '$clientMsg'")
        Some(AuthResponse.Challenge(s"""{"alert":"invalid user id"}"""))
    }
  }

  override def processJSONAuthMessage (conn: SocketConnection, msgTag: ByteSlice, parser: JsonPullParser): Option[AuthResponse] = {
    msgTag match {
      case AUTH_USER =>
        val uid = parser.quotedValue.toString
        info(s"received auth request for $uid from ${conn.remoteAddress}")
        Some(AuthResponse.Accept( uid, s"""{"accept":"$uid"}""")) // no need to parse anything

      case _ => None
    }
  }


  def docRequestScript (requestUrl: Uri, postUrl: String): String = {
    s"""
      ${AuthMethod.commonDocRequestScripting(requestUrl, postUrl)}

      function authenticate() {
        const uid = document.getElementById('uid').value;

        if (uid.length == 0){
          authAlert("please enter non-empty user");
          return;
        }

        getResponse( {authUser: uid})
        .then( serverMsg => {
          if (serverMsg.accept) {
            parent.location.replace( '$requestUrl');

          } else if (serverMsg.alert) {
            authAlert( serverMsg.alert);

          } else if (serverMsg.reject) {
            let topDoc = parent.document;
            topDoc.getElementById('auth').style.display='none'
            topDoc.documentElement.innerHTML = serverMsg.reject;
          }
        });
      }
    """
  }

  def wsRequestScript (): String = {
    s"""
      ${AuthMethod.commonWsRequestScripting()}

      //--- (response) action triggered by auth dialog
      function authenticate() {
        const uid = document.getElementById('uid').value;

        if (uid.length == 0){
          authAlert("please enter user and password");
          return;
        }

        sendAndHandle( {authUser: uid}, function (ws,msg) {
          return handleFinalServerResponse(msg);
        });
      }
    """
  }

  override def authPage(remoteAddress: InetSocketAddress, requestPrefix: String, requestUrl: Uri, postUrl: String): String = {
    authPage( docRequestScript(requestUrl, postUrl))
  }

  override def authPage(remoteAddress: InetSocketAddress): String = {
    authPage( wsRequestScript())
  }

  def authPage (script: String): String = AuthMethod.userAuthPage(script)
}

