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
import com.typesafe.config.Config
import gov.nasa.race.common.ConstUtf8Slice.utf8
import gov.nasa.race.common.{BatchedTimeoutMap, ByteSlice, JsonPullParser, StringJsonPullParser, TimeoutSubject}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.http.AuthMethod.scriptNode
import gov.nasa.race.uom.Time
import gov.nasa.race.util.ArrayUtils
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PwAuthMethod {
  val AUTH_USER = utf8("authUser")
  val AUTH_CREDENTIALS = utf8("authCredentials")
}

/**
  * a simple shared-secret password AuthMethod
  */
class PwAuthMethod (config: Config) extends AuthMethod {
  import PwAuthMethod._

  case class PendingRequest (uid: String, clientAddr: InetSocketAddress) extends TimeoutSubject {
    var failed: Int = 0 // count of consecutive failures

    val timeout: Time = requestTimeout
    def timeoutExpired(): Unit = {
      // TBD
    }
  }

  val users = new PwUserStore(config.getVaultableStringOrElse("users", ".users"))

  val requestTimeout: FiniteDuration = config.getFiniteDurationOrElse("timeout", 1.minute)
  val pendingRequests = new BatchedTimeoutMap[InetSocketAddress,PendingRequest](requestTimeout + 200.milliseconds)
  val maxFailed = config.getIntOrElse("max-failed", 5)

  //--- the server side of the protocol (this can be used both from a document request and a websocket message handler)

  override def processJSONAuthMessage (conn: SocketConnection, msgTag: ByteSlice, parser: JsonPullParser): Option[AuthResponse] = {
    val clientAddress = conn.remoteAddress

    msgTag match {
      case AUTH_USER =>
        val uid = parser.quotedValue.toString
        info(s"received auth request for $uid from ${conn.remoteAddress}")

        val isPending = pendingRequests.get(clientAddress) match {
          case Some(req) =>
            if (req.uid != uid) {
              pendingRequests -= clientAddress
              false // new uid counts as a new request
            } else true
          case None => false
        }

        if (users.isRegisteredUser(uid)) {
          if (!isPending) pendingRequests += (clientAddress -> PendingRequest(uid,clientAddress))
          info(s"responding to valid auth request for known user '$uid'")
          Some(AuthResponse.Challenge(s"""{"requestCredentials":"$uid"}"""))
        } else {
          // we could use the clientAddress here to register the user/pw, or use additional protocol
          info(s"rejecting auth request for unknown user '$uid'")
          Some(AuthResponse.Reject(s"""{"reject":"unknown user $uid"}"""))
        }

      case AUTH_CREDENTIALS => // client provides credentials to finish auth
        pendingRequests.get(clientAddress) match {
          case Some(req) =>
            val buf = ArrayBuffer.empty[Byte]
            val pw = parser.readCurrentByteArrayInto(buf).toArray
            ArrayUtils.fill(buf, 0.toByte) // don't leave copies around

            users.getUser(req.uid, pw) match {
              case None =>
                info(s"rejected credentials for user '${req.uid}'")
                req.failed += 1
                if (req.failed >= maxFailed) {
                  pendingRequests -= clientAddress
                  Some(AuthResponse.Reject("""{"reject":"max attempts exceeded"}"""))
                } else {
                  Some(AuthResponse.Challenge(s"""{"alert":"invalid password (${maxFailed - req.failed} attempts left)"}"""))
                }

              case Some(user) =>
                info(s"accepted credentials for user '${user.uid}'")
                pendingRequests -= clientAddress
                Some(AuthResponse.Accept(user.uid, s"""{"accept":"${user.uid}"}"""))
            }

          case None =>
            info(s"rejecting unsolicited credentials from $clientAddress")
            Some(AuthResponse.Reject("""{"reject":"no pending auth request"}"""))
        }
    }
  }

  //--- the client side of the protocol that is transmitted with the loginElement response

  def docRequestScript (requestUrl: String, postUrl: String): String = { s"""
    function authenticate() {
      const uid = document.getElementById('uid').value;
      const pw = Array.from( new TextEncoder().encode( document.getElementById('pw').value));

      if (uid.length == 0 || pw.length == 0){
        document.getElementById('alert').innerText = "please enter non-empty user and password";
        return;
      }

      getResponse( {authUser: uid})
      .then( response => response.json())
      .then( serverMsg => {
        if (uid == serverMsg.requestCredentials) {
          getResponse({authCredentials: pw})
          .then( response => response.json())
          .then( serverMsg => {
            if (serverMsg.accept) {
              parent.location.replace( '$requestUrl');
            } else if (serverMsg.alert) {
              document.getElementById('alert').innerText = serverMsg.alert;
            } else if (serverMsg.reject) {
              let topDoc = parent.document;
              topDoc.getElementById('auth').style.display='none'
              topDoc.documentElement.innerHTML = serverMsg.reject;
            }
          });
        }
      });
    }

    async function getResponse (data) {
      let request = {method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'same-origin',
                     headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)};
      return await fetch('/$postUrl', request)
    }

    function checkPwEnter(event) {
      if (event.key=='Enter') authenticate();
    }
    """
  }

  def wsRequestScript (): String = {
    s"""
        // this assumes a global var 'wsAuthHandler' that is defined in the document
        parent.wsAuthHandler = handleStartAuth;

        var send = noSend;

        function noSend (data) {
          console.log("not connected");
        }

        //--- server reaction to protected operation request
        function handleStartAuth (ws,msg) {
          if (Object.keys(msg)[0] == "startAuth") {
            parent.document.getElementById("auth").style.display='block'; // make modal auth layer visible
            send = function (data) { ws.send(JSON.stringify(data)); }

            const uid = msg.startAuth;
            if (uid.length > 0) document.getElementById('uid').value = uid;  // use operation supplied uid to init auth layer

            return true;

          } else {
            return false; // not handled
          }
        }

        //--- (response) action triggered by auth dialog
        function authenticate() {
          const uid = document.getElementById('uid').value;
          const pw = Array.from( new TextEncoder().encode( document.getElementById('pw').value));

          if (uid.length == 0 || pw.length == 0){
            document.getElementById('alert').innerText = "please enter non-empty user and password";
            return;
          }

          sendAndHandle( {authUser: uid}, function (ws,msg) {
            if (Object.keys(msg)[0] == "requestCredentials") {
              if (uid == msg.requestCredentials){

                sendAndHandle( {authCredentials: pw}, function (ws,msg) {
                  switch (Object.keys(msg)[0]) {
                    case "accept": // no further action here - server will start authenticated session
                      finishAuth();
                      return true;
                    case "alert":
                      document.getElementById('alert').innerText = msg.alert;
                      return true;
                    case "reject":
                      alert("server rejected authentication with: " + msg.reject);
                      finishAuth();
                      return true;
                  }
                  return false;
                });
              }
              return true;

            } else {
              return false; // not handled
            }
          });
        }

        function finishAuth() {
          parent.document.getElementById("auth").style.display='none';  // hide auth dialog
          parent.wsAuthHandler = handleStartAuth;
          send = noSend;
        }

        // syntactic suger - more readable to specify the outgoing message and then the handler (but exec in reverse)
        function sendAndHandle (data,newHandler) {
          parent.wsAuthHandler = newHandler;
          send(data);
        }

        function checkPwEnter(event) {
          if (event.key=='Enter') authenticate();
        }
     """
  }

  // the authentication iframe content for document requests
  override def authPage(remoteAddress: InetSocketAddress, requestUrl: String, postUrl: String): String = {
    authPage( docRequestScript(requestUrl, postUrl))
  }

  // the auth iframe content for websocket requests
  override def authPage(remoteAddress: InetSocketAddress): String = {
    authPage( wsRequestScript())
  }

  def authPage (authScript: String): String = {
    html(
      head(
        link(rel:="stylesheet", tpe:="text/css", href:="/auth.css"),
        scriptNode( authScript),
      ),
      body()(
        div(cls := "authForeground")(
          span(cls := "authCancel", title := "Close Modal", cls := "authCancel",
            onclick := "parent.document.getElementById('auth').style.display='none'")("×"),
          div(cls := "authImgContainer")(
            img(src := s"/auth.svg", alt := "Avatar", cls := "authImg")
          ),
          div(cls := "authFormContainer")(
            div(id := "alert", cls := "authAlert")(""),
            table(style := "border-style: none;")(
              tr(
                td(cls := "authLabel")("User"),
                td(style := "width: 99%;")(
                  input(`type` := "text", id := "uid", placeholder := "Enter Username", required := true,
                    autofocus := true, cls := "authTextInput")
                )
              ),
              tr(
                td(cls:="authLabel")("Password"),
                td(
                  input(`type` := "password", id := "pw", placeholder := "Enter Password", required := true,
                    autocomplete := "on", onkeyup := "checkPwEnter(event);", cls := "authPwInput")
                )
              )
            ),
            button(`type` := "button", onclick := "authenticate();", cls := "authButton")("authenticate")
          )
        )
      )
    ).render
  }
}
