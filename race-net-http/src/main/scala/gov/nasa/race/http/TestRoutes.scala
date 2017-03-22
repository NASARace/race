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
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ParentContext, RaceActorRec, SubscribingRaceActor}

import scalatags.Text.all.{head => htmlHead, _}

/**
  * a simple statically configured test route without actor
  */
class TestRouteInfo (val parent: ParentContext, val config: Config) extends RaceRouteInfo {
  val request = config.getStringOrElse("request", "test")
  val response = config.getStringOrElse("response", "Hello from RACE")

  def route = {
    path(request) {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, response))
      }
    }
  }
}

/**
  * a test route that uses a web socket for data exchange
  */
class TestRefresh (val parent: ParentContext, val config: Config) extends RaceRouteInfo {

  class RouteActor (val config: Config) extends SubscribingRaceActor {
    info("$name created")

    override def handleMessage = {
      case BusEvent(_,msg,_) => data = msg.toString
    }
  }

  parent.addChild(RaceActorRec(parent.actorOf(Props(new RouteActor(config)),"refreshRoute"),config))

  var data = "?" // to be set by actor

  val page = html(
    htmlHead(
      script(raw("""
                var refresher = setInterval(loadData,5000);

                function loadData(){
                  var req = new XMLHttpRequest();
                  req.onreadystatechange = function() {
                    if (this.readyState == 4 && this.status == 200) {
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
    body(onload:="refresher()")(
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