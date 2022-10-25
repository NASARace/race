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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.sse.ServerSentEvent

import java.net.InetSocketAddress
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, ParentActor, RaceDataClient}
import scalatags.Text.all.{head => htmlHead, _}

import scala.collection.immutable.Iterable
import scala.collection.Seq

/*
 * a collection of generic test routes that can be used to test network connection and client compatibility.
 * We keep this in race-net-http instead of the -test module so that network connection can be checked without
 * the need for a full RACE installation
 *
 * the TestXX RouteInfos provided here are used from a number of config/net/ config files, such as
 * http-server.conf, sse-server.conf, ws-server.conf and more
 */


/**
  * a simple statically configured test route without actor
  */
class TestRouteInfo (val parent: ParentActor, val config: Config) extends RaceRouteInfo {
  val response = config.getStringOrElse("response", "Hello from RACE")

  override def route = {
    get {
      path(requestPrefixMatcher) {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, response))
      }
    }
  }
}


/**
  * test route that serves a page which requires manual user authentication
  */
class TestAuthorized (val parent: ParentActor, val config: Config) extends AuthRaceRoute {
  var count = 0

  def page = html(
    body(
      p(s"the supersecret answer #$count to the ultimate question of life, the universe and everything is:"),
      p(b("42")),
      p("(I always knew there was something wrong with the universe)"),
      logoutLink
    )
  )

  override def route = {
    get {
      path(requestPrefixMatcher) {
        count += 1
        completeAuthorized(){
          HttpEntity(ContentTypes.`text/html(UTF-8)`, page.render)
        }
      }
    }
  }
}

/**
  * a test route that uses a periodically run script for data update via XMLHttpRequest calls
  */
class TestRefresh (val parent: ParentActor, val config: Config) extends SubscribingRaceRoute {

  private var data: String = "?" // to be set by actor

  override def receiveData: Receive = {
    case newData: Any => data = newData.toString
  }

  val page = html(
    htmlHead(
      script(raw(s"""
                function loadData(){
                  var req = new XMLHttpRequest();
                  req.onreadystatechange = function() {
                    if (this.readyState == 4 && this.status == 200) {
                      console.log("new data: " + req.responseText);
                      document.getElementById('data').innerHTML = req.responseText;
                    } else if (this.status == 404){
                      clearInterval(refresher)
                    }
                  };
                  req.open('GET', '$requestPrefix/data', true);
                  req.send();
                }
        """))
    ),
    body(onload:="setInterval(loadData,5000);")(
      h1("RACE Data Reload Test"),
      div(id:="data")(data)
    )
  )

  override def route: Route = {
    get {
      pathPrefix(requestPrefixMatcher) {
        path("data") {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, data))
        } ~ pathEnd {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, page.toString))
        }
      }
    }
  }
}

/**
  * test route that pushes data to all connections
  */
class TestPusher (val parent: ParentActor, val config: Config) extends PushWSRaceRoute with RaceDataClient {

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {
        promoteToWebSocket()
      }
    }
  }

  override def receiveData: Receive = {
    case BusEvent(_,msg,_) =>
      info(s"received from bus: $msg")
      push( TextMessage.Strict(msg.toString))
  }
}

/**
  * test route that serves a user authorized page which uses a web socket to receive data pushed by the server
  * to all connections
  */
class TestAuthorizedPusher (val parent: ParentActor, val config: Config) extends PushWSRaceRoute with AuthWSRaceRoute {

  val page = html(
    htmlHead(
      script(raw(s"""
         function websock_test() {
            if ("WebSocket" in window) {
               console.log("WebSocket is supported by your Browser!");
               var ws = new WebSocket("wss://localhost:8080/$requestPrefix/ws");

               ws.onopen = function() {
                  ws.send("Hi server!");
                  console.log("message is sent...");
               };

               ws.onmessage = function (evt) {
                  var received_msg = evt.data;
                  document.getElementById("data").innerHTML = received_msg;
                  console.log("got message: " + received_msg);
               };

               ws.onclose = function() {
                  console.log("connection is closed...");
               };
            } else {
               console.log("WebSocket NOT supported by your Browser!");
            }
         }
      """)
      )
    ),
    body(onload:="websock_test()")(
      h1("RACE WebSocket Test"),
      div(id:="data")("no data yet..")
    )
  )

  override def route: Route = {
    get {
      pathPrefix(requestPrefixMatcher) {
        pathEnd {  // requesting page that opens websocket
          completeAuthorized() {
            HttpEntity(ContentTypes.`text/html(UTF-8)`, page.render)
          }
        } ~ path("ws") { // directly requesting websocket
          promoteToWebSocket()
        }
      }
    }
  }
}

/**
  * test route that uses a web socket to echo messages
  */
class EchoService (val parent: ParentActor, val config: Config) extends ProtocolWSRaceRoute {

  override protected def handleIncoming (ctx: WSContext, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        val msgText = tm.text
        info(s"route $name processing incoming: $msgText")
        discardMessage(m)
        TextMessage.Strict(s"Echo [$msgText]") :: Nil
      case _ =>
        discardMessage(m)
        Nil
    }
  }

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {
        promoteToWebSocket()
      }
    }
  }
}


/**
  * a self-authenticated web socket service without other content
  *
  * this service echos incoming messages to the requester and otherwise periodically pushes data to all connections
  */
class AuthorizedEchoPushService (val parent: ParentActor, val config: Config) extends PushWSRaceRoute with PwAuthorizedWSRoute with RaceDataClient {

  override protected def handleIncoming (ctx: WSContext, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        val msgText = tm.text
        info(s"echo incoming: '$msgText' from ${ctx.sockConn.remoteAddress}")
        TextMessage.Strict(s"Echo [$msgText]") :: Nil
      case other =>
        info(s"incoming message ignored: $other")
        Nil
    }
  }

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {
        promoteAuthorizedToWebSocket(User.UserRole)
      }
    }
  }

  override def receiveData: Receive = {
    case BusEvent(_,msg,_) => push(TextMessage.Strict(msg.toString))
  }
}

class TestSSERoute (val pa: ParentActor, val conf: Config) extends SiteRoute(pa,conf) with PushSSERoute {

  override def route: Route = {
    get {
      path( requestPrefix / "stream") {
        promoteToStream()
      } ~ siteRoute
    }
  }

  // no accumulation here, we just push out whatever we get
  override def receiveData: Receive = {
    case BusEvent(_,msg,_) => push(ServerSentEvent(msg.toString))
  }
}