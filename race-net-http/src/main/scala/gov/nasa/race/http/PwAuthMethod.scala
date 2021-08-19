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
import com.typesafe.config.Config
import gov.nasa.race.common.ConstUtf8Slice.utf8
import gov.nasa.race.common.{BatchedTimeoutMap, ByteSlice, JsonPullParser, StringJsonPullParser, TimeoutSubject}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.http.AuthMethod.{commonDocRequestScripting, scriptNode}
import gov.nasa.race.uom.Time
import gov.nasa.race.util.ArrayUtils
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PwAuthMethod {
  val AUTH_USER = utf8("authUser")
  val AUTH_CREDENTIALS = utf8("authCredentials")

  val authInitScript: String = """
     function initAuth() {
       let qs = document.location.search;
       let urlParams = new URLSearchParams(qs);

       let uid = urlParams.get('uid');
       if (uid) document.getElementById("uid").value=uid;

       let pw = urlParams.get('pw');
       if (pw) document.getElementById("pw").value=pw;
     }
  """
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

  def docRequestScript (requestUrl: Uri, postUrl: String): String = {
    s"""
      ${AuthMethod.commonDocRequestScripting(requestUrl,postUrl)}

      function authenticate() {
        const uid = document.getElementById('uid').value;
        const pw = Array.from( new TextEncoder().encode( document.getElementById('pw').value));

        if (uid.length == 0 || pw.length == 0){
          authAlert("please enter non-empty user and password");
          return;
        }

        getResponse( {authUser: uid})
        .then( serverMsg => {
          if (uid == serverMsg.requestCredentials) {
            getResponse({authCredentials: pw})
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
        const pw = Array.from( new TextEncoder().encode( document.getElementById('pw').value));

        if (uid.length == 0 || pw.length == 0){
          authAlert("please enter user and password");
          return;
        }

        sendAndHandle( {authUser: uid}, function (ws,msg) {
          if (Object.keys(msg)[0] == "requestCredentials") {
            if (uid == msg.requestCredentials){
              sendAndHandle( {authCredentials: pw}, function (ws,msg) {
                return handleFinalServerResponse(msg);
              });
            }
            return true;

          } else {
            return false; // not handled
          }
        });
      }
     """
  }

  // the authentication iframe content for document requests
  override def authPage(remoteAddress: InetSocketAddress, requestPrefix: String, requestUrl: Uri, postUrl: String): String = {
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
        scriptNode( PwAuthMethod.authInitScript)
      ),
      body(onload:="initAuth();")(
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
                    autocomplete := "on", onkeyup := "authCheck(event);", cls := "authPwInput")
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
