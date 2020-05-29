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

import akka.actor.Props
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ParentActor, SubscribingRaceActor}
import scalatags.Text.all.{head => htmlHead, _}

import scala.collection.immutable

/**
  * a simple statically configured test route without actor
  */
class TestRouteInfo (val parent: ParentActor, val config: Config) extends RaceRouteInfo {
  val request = config.getStringOrElse("request", "test")
  val response = config.getStringOrElse("response", "Hello from RACE")

  override def route = {
    path(request) {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, response))
      }
    }
  }
}

class TestPreAuthorized(val parent: ParentActor, val config: Config) extends PreAuthorizedRaceRoute {
  val request = config.getStringOrElse("request", "autoSecret")
  var count = 0

  override def route = {
    path(request) {
      get {
        completeAuthorized(User.UserRole){
          count += 1
          HttpEntity(ContentTypes.`application/json`, s"""{ "count": $count }""")
        }
      }
    }
  }
}

class TestAuthorized (val parent: ParentActor, val config: Config) extends AuthorizedRaceRoute {
  val request = config.getStringOrElse("request", "secret")
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
    path(request) {
      get {
        count += 1
        completeAuthorized(User.UserRole){
          HttpEntity(ContentTypes.`text/html(UTF-8)`, page.render)
        }
      }
    }
  }
}

/**
  * a test route that uses a script for dynamic data update
  */
class TestRefresh (val parent: ParentActor, val config: Config) extends SubscribingRaceRoute {

  private var data: String = "?" // to be set by actor

  override def setData(newData: Any): Unit = {
    data = newData.toString
  }

  val page = html(
    htmlHead(
      script(raw("""
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
                  req.open('GET', 'refresh/data', true);
                  req.send();
                }
        """))
    ),
    body(onload:="setInterval(loadData,5000);")(
      h1("RACE Data Reload Test"),
      div(id:="data")(data)
    )
  )

  def route = {
    get {
      pathPrefix("refresh") {
        path("data") {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, data))
        } ~ pathEnd {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, page.toString))
        }
      }
    }
  }
}

class TestPusher (val parent: ParentActor, val config: Config) extends PushWSRaceRoute {

  override def route = {
    get {
      path("data") {
        promoteToWebSocket
      }
    }
  }
}

class TestAuthorizedPusher (val parent: ParentActor, val config: Config) extends PushWSRaceRoute with AuthorizedWSRoute {

  val page = html(
    htmlHead(
      script(raw("""
         function websock_test() {
            if ("WebSocket" in window) {
               console.log("WebSocket is supported by your Browser!");
               var ws = new WebSocket("wss://localhost:8080/ws");

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
      path("data") {
        completeAuthorized(User.UserRole){
          HttpEntity(ContentTypes.`text/html(UTF-8)`, page.render)
        }
      } ~ path("ws") {
        promoteAuthorizedToWebSocket(User.UserRole)
      }
    }
  }

}

class EchoService (val parent: ParentActor, val config: Config) extends ProtocolWSRaceRoute {

  override protected val handleMessage = {
    case tm: TextMessage.Strict =>
      val msgText = tm.text
      info(s"route $name processing incoming: $msgText")
      TextMessage(Source.single(s"Echo [$msgText]")) :: Nil
  }

  override def route: Route = {
    get {
      path("echo") {
        promoteToWebSocket
      }
    }
  }
}
