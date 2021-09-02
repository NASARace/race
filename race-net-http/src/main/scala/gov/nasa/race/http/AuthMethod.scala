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

import akka.http.scaladsl.model.{ContentType, ContentTypes, Uri}
import gov.nasa.race.common.{ByteSlice, JsonPullParser, LogWriter, StringJsonPullParser}
import gov.nasa.race.util.{ClassUtils, StringUtils}
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
    ClassUtils.getResourceAsUtf8String(getClass,"auth.css") match {
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

  val authFrameStyle = "border:none;width:100%;height:100%;z-index:1;position:fixed;left:0;top:0;background-color: rgba(0,0,0,0.4);"

  def scriptNode (code: String): Text.RawFrag = raw(s"""<script>$code</script>""")

  // TODO - we should handle URLs without scheme and authority
  def authPathPrefix (requestUrl: String): String = {
    val i0 = StringUtils.indexOfNth(requestUrl,'/',3)
    val i1 = requestUrl.lastIndexOf('/')

    if ((i1 == requestUrl.length-1) || (requestUrl.indexOf('.', i1) > 0)) {
      if (i0 == i1) "" else requestUrl.substring(i0+1,i1)
    } else {
      requestUrl.substring(i0+1)
    }
  }

  //--- common script code

  def commonRequestScripting(): String = {
    """
      function authCheck(event) {
        if (event.key=='Enter') authenticate();
      }

      function authAlert (msg) {
        let alertElem = document.getElementById("alert");
        if (alertElem) {
          alertElem.innerText = msg;
        } else {
          alert(msg);
        }
      }
    """
  }

  def commonDocRequestScripting (requestUrl: Uri, postUrl: String): String = {
    s"""
      ${commonRequestScripting()}

      async function getResponse (data) {
        let request = {method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)};
        return await fetch('/$postUrl', request).then( response => response.json())
      }

      function finishAuth (msg, requestUrl) {
        if (msg.accept){ // registration completed successfully (got session cookie in Set-Cookie header)
          parent.location.replace( requestUrl);
        } else {
          parent.document.getElementById('auth').style.display='none'
        }
      }
    """
  }

  def commonWsRequestScripting(): String = {
    s"""
       ${commonRequestScripting()}

        // document global 'wsAuthHandler' hook used by application specific client side websocket handler
        parent.wsAuthHandler = handleStartAuth;

        var send = noSend;  // the function to send data to the server

        //--- utility functions

        function noSend (data) {
          console.log("not connected");
        }

        function handleFinalServerResponse (msg) {
          switch (Object.keys(msg)[0]) {
            case "accept":
              finishAuth();
              return true;

            case "reject":
              finishAuth();
              alert("user authentication rejected: " + msg.reject)
              return true;

            case "alert":
              authAlert( msg.alert);
              return true;

            default:
              return false;
          }
        }

        function finishAuth () {
          parent.document.getElementById("auth").style.display='none';  // hide auth dialog
          parent.wsAuthHandler = handleStartAuth;
          send = noSend;
        }

        // syntactic sugar - more readable to specify the outgoing message and then the handler (but exec in reverse)
        function sendAndHandle (data,newHandler) {
          parent.wsAuthHandler = newHandler;
          send(data);
        }

        //--- activate auth UI and respective

        function handleStartAuth (ws,msg) {  // called by the application handler through global 'wsAuthHandler'
          console.log(msg);
          if (Object.keys(msg)[0] == "startAuth") {
            if (parent){
              let authElem = parent.document.getElementById("auth");
              if (authElem) {
                authElem.style.display='block'; // make modal auth layer visible
                send = function (data) { ws.send(JSON.stringify(data)); }

                const uid = msg.startAuth;
                if (uid.length > 0) {
                  let uidElem = document.getElementById('uid');
                  if (uidElem) uidElem.value = uid;  // use operation supplied uid to init auth layer
                }
                return true;  // auth UI active

              } else {
                Console.log("no auth overlay");
              }
            } else {
              Console.log("no parent document");
            }
            alert("authentication failure");
            return false;

          } else {
            return false; // message not handled
          }
        }
    """
  }

  val authInitScript: String = """
     function initAuth() {
       let qs = document.location.search;
       let urlParams = new URLSearchParams(qs);

       let uid = urlParams.get('uid');
       if (uid) document.getElementById("uid").value=uid;
     }
  """

  def userAuthPage (script: String): String = {
    html(
      head(
        link(rel:="stylesheet", tpe:="text/css", href:="/auth.css"),
        scriptNode( script),
        scriptNode( AuthMethod.authInitScript)
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
                td(cls := "authLabel")(b("User")),
                td(style := "width: 99%;")(
                  input(`type` := "text", id := "uid", placeholder := "Enter Username", required := true,
                    autofocus := true, cls := "authTextInput", onkeyup:="authCheck(event);")
                )
              )
            ),
            button(`type` := "button", onclick := "authenticate();", cls := "authButton")("authenticate")
          )
        )
      )
    ).render
  }

  val authQueryRE = "&?auth=\\((.*)\\)$".r

  def authQueryString (requestUrl: Uri): String = {
    requestUrl.rawQueryString match {
      case Some(qs) =>
        authQueryRE.findFirstMatchIn(qs) match {
          case Some(m) => // extract the auth part
            val i0 = m.start
            val aq = m.group(1).toString.replace(',','&')
            if (i0 ==0) { // 'auth' is only query param
              Uri.Query( s"tgt=${requestUrl.copy(rawQueryString = None)}&$aq").toString
            } else {
              Uri.Query( s"tgt=${requestUrl.withRawQueryString (qs.substring(0,i0))}&$aq").toString
            }

          case None => Uri.Query(s"tgt=$requestUrl").toString // no auth part - pass the query into tgt
        }

      case None => Uri.Query(s"tgt=$requestUrl").toString // no query part - just encode the 'tgt=<url>'
    }
  }
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

  def loginPage (remoteAddress: InetSocketAddress, requestPrefix: String, requestUrl: Uri, postUrl: String): String = {
    html(
      body()(
        iframe(id:="auth", src:=s"$requestPrefix/auth.html?${authQueryString(requestUrl)}", style:=authFrameStyle)(),
        s"you need to authenticate in order to access $requestUrl"
      )
    ).render
  }

  def authPage (remoteAddress: InetSocketAddress, requestPrefix: String, requestUrl: Uri, postUrl: String): String // the document version

  def authPage (remoteAddress: InetSocketAddress): String // the websock version

  def authCSS(): String = defaultAuthCSS

  def authSVG(): Array[Byte] = defaultAuthSVG

  def startAuthMessage (uid: String): String = s"""{"startAuth":"$uid"}"""
  def rejectAuthMessage (reason: String): String = s"""{"reject":"$reason"}"""

  // override if this method needs to store credentials etc
  def shutdown(): Unit = {}
}
