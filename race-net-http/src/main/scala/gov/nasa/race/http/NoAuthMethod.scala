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
  val AUTH_REQUEST = utf8("authRequest")
  val authRequestRE = """ *\{ *"authRequest":.*""".r

  override def processAuthMessage (conn: SocketConnection, clientMsg: String): Option[AuthResponse] = {
    if (authRequestRE.matches(clientMsg)) Some(AuthResponse.Accept("*", s"""{"accept":"*"}""")) else None
  }

  override def processJSONAuthMessage (conn: SocketConnection, msgTag: ByteSlice, parser: JsonPullParser): Option[AuthResponse] = {
    msgTag match {
      case AUTH_REQUEST => Some(AuthResponse.Accept("*", s"""{"accept":"*"}""")) // no need to parse anything
      case _ => None
    }
  }

  override def loginPage(remoteAddress: InetSocketAddress, requestUrl: String, postUrl: String): String = {
    html(
      head(
        scriptNode(s"""
         function authenticate() {
           getResponse({authRequest:'$requestUrl'})
           .then( response => response.json())
           .then( serverMsg => {
             if (serverMsg.accept) {
               location.replace( '$requestUrl');
             } else if (serverMsg.alert) {
               document.getElementById('alert').innerText = serverMsg.alert;
             } else if (serverMsg.reject) {
               document.documentElement.innerHTML = serverMsg.reject;
             }
           });
         }

         async function getResponse (data) {
           let request = {method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'same-origin',
                          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)};
           return await fetch('/$postUrl', request)
         }
         """
        )
      ),
      body(onload := s"authenticate();")(
        p(s"no need to authenticate"),
      )
    ).render
  }

  override def authPage(remoteAddress: InetSocketAddress, requestUrl: String, postUrl: String): String = "no authentication required"

  override def authPage(remoteAddress: InetSocketAddress): String = ???
}

