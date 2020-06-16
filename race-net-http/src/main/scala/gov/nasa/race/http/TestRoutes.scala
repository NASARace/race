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

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{DataConsumerRaceActor, ParentActor, PeriodicRaceActor, RaceDataConsumer}
import gov.nasa.race.uom.DateTime
import scalatags.Text.all.{head => htmlHead, _}

import scala.collection.mutable

/*
 * a collection of generic test routes that can be used to test network connection and client compatibility.
 * We keep this in race-net-http instead of the -test module so that network connection can be checked without
 * the need for a full RACE installation
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
  * test route that requires automated user authentication by means of login POST requests
  */
class TestPreAuthorized(val parent: ParentActor, val config: Config) extends PreAuthorizedRaceRoute {
  var count = 0

  override def route = {
    get {
      path(requestPrefixMatcher) {
        completeAuthorized(User.UserRole){
          count += 1
          HttpEntity(ContentTypes.`application/json`, s"""{ "count": $count }""")
        }
      }
    }
  }
}

/**
  * test route that serves a page which requires manual user authentication
  */
class TestAuthorized (val parent: ParentActor, val config: Config) extends AuthorizedRaceRoute {
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
        completeAuthorized(User.UserRole){
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

  override def setData(newData: Any): Unit = {
    data = newData.toString
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

  def route = {
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
class TestPusher (val parent: ParentActor, val config: Config) extends PushWSRaceRoute {

  override def route = {
    get {
      path(requestPrefixMatcher) {
        promoteToWebSocket
      }
    }
  }
}

/**
  * test route that serves a user authorized page which uses a web socket to receive data pushed by the server
  * to all connections
  */
class TestAuthorizedPusher (val parent: ParentActor, val config: Config) extends PushWSRaceRoute with AuthorizedWSRoute {

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
        pathEnd {
          completeAuthorized(User.UserRole) {
            HttpEntity(ContentTypes.`text/html(UTF-8)`, page.render)
          }
        } ~ path("ws") {
          promoteAuthorizedToWebSocket(User.UserRole)
        }
      }
    }
  }
}

/**
  * test route that uses a web socket to echo messages
  */
class EchoService (val parent: ParentActor, val config: Config) extends ProtocolWSRaceRoute {

  override protected val handleMessage = {
    case tm: TextMessage.Strict =>
      val msgText = tm.text
      info(s"route $name processing incoming: $msgText")
      TextMessage(Source.single(s"Echo [$msgText]")) :: Nil
  }

  override def route: Route = {
    get {
      path(requestPrefixMatcher) {
        promoteToWebSocket
      }
    }
  }
}


//--- example of a site that dynamically updates data with a web socket

object TabDataService {
  case class FieldCatalog (fields: Seq[String])
  case class ProviderList (names: Seq[String])
  case class Provider (name: String, var date: DateTime, var fieldValues: Map[String,Int])

  // the (ordered) fields catalog
  val fieldCatalog = FieldCatalog(Seq("field_1", "field_2", "field_3", "field_4", "field_5", "field_6", "field_7", "field_8", "field_9"))

}

/**
  * the actor that collects/computes the model (data) and reports it to its data consumer RaceRouteInfo
  * this should not be concerned about any RaceRouteInfo specifics (clients, data formats etc.)
  */
class TabDataServiceActor (_dc: RaceDataConsumer, _conf: Config) extends DataConsumerRaceActor(_dc,_conf)
                                                                                       with PeriodicRaceActor {
  import TabDataService._
  var nextProviderIdx = 0

  val data = Array(
    Provider("provider_1", DateTime.now, Map(("field_1",1100), ("field_3",1300), ("field_8",1800))),
    Provider("provider_2", DateTime.now, Map(("field_3",2300), ("field_4",2400), ("field_6",2600))),
    Provider("provider_3", DateTime.now, Map(("field_1",3100), ("field_2",3200), ("field_5",3500), ("field_8",3800))),
    Provider("provider_4", DateTime.now, Map(("field_1",4100), ("field_3",4300), ("field_4",4400), ("field_6",4600), ("field_7",4700))),
    Provider("provider_5", DateTime.now, Map(("field_2",5200), ("field_4",5400), ("field_7",5700), ("field_8",5800))),
    Provider("provider_6", DateTime.now, Map(("field_5",6500), ("field_6",6600), ("field_7",6700), ("field_9",6900))),
    Provider("provider_7", DateTime.now, Map(("field_2",7200), ("field_4",7400), ("field_7",7700))),
    Provider("provider_8", DateTime.now, Map(("field_3",8300), ("field_4",8400), ("field_9",8900))),
    Provider("provider_9", DateTime.now, Map(("field_2",9200), ("field_3",9300), ("field_4",9400), ("field_5",9500), ("field_6",9600), ("field_8",9800)))
  )

  // the (ordered) provider list
  val providerList = ProviderList(data.map(_.name).toIndexedSeq)

  override def onStartRaceActor(originator: ActorRef): Boolean = {

    // set the initial data
    setData(fieldCatalog)
    setData(providerList)
    data.foreach(setData)

    super.onStartRaceActor(originator)
  }

  // periodically mutate the next provider
  override def onRaceTick: Unit = {
    if (nextProviderIdx >=0 && nextProviderIdx < data.length) {
      val provider = data(nextProviderIdx)
      provider.date = DateTime.now
      provider.fieldValues = provider.fieldValues.map( e=> (e._1, e._2+1))
      nextProviderIdx = (nextProviderIdx + 1) % providerList.names.length

      setData(provider)
    } else warning(s"data index out of range: $nextProviderIdx")
  }
}

/**
  * the RaceRouteInfo that serves the content to display and update Provider data
  */
class TabDataService (parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                 with PushWSRaceRoute with RaceDataConsumer {
  import TabDataService._

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  val writer = new JsonWriter

  // we store the data in a format that is ready to send
  var fieldCatalog: Option[TextMessage] = None
  var providerList: Option[TextMessage] = None
  val providerData = mutable.Map.empty[String,TextMessage]

  def serializeProviderList (providerList: ProviderList): String = {
    writer.clear
      .beginObject
      .writeStringMember("msgType","ProviderNames")
      .writeMemberName("providers")
      .writeStringValues(providerList.names)
      .endObject
      .toJson
  }

  def serializeFieldCatalog (fieldCatalog: FieldCatalog): String = {
    writer.clear
      .beginObject
      .writeStringMember("msgType","FieldCatalog")
      .writeMemberName("fields")
      .writeStringValues(fieldCatalog.fields)
      .endObject
      .toJson
  }

  def serializeProvider (provider: Provider): String = {
    writer.clear
      .beginObject
      .writeStringMember("msgType","ProviderData")
      .writeStringMember("name",provider.name)
      .writeLongMember("date", provider.date.toEpochMillis)
      .writeMemberName("fieldValues")
      .writeIntMembers(provider.fieldValues)
      .endObject
      .toJson
  }

  override protected def instantiateActor: DataConsumerRaceActor = new TabDataServiceActor(this,config)

  override def setData (data:Any): Unit = {
    data match {
      case p: Provider =>
        val msg = TextMessage(serializeProvider(p))
        providerData.put(p.name,msg)
        push(msg)

      case p: ProviderList =>
        val msg = TextMessage(serializeProviderList(p))
        providerList = Some(msg)
        push(msg)

      case c: FieldCatalog =>
        val msg = TextMessage(serializeFieldCatalog(c))
        fieldCatalog = Some(msg)
        push(msg)
    }
  }

  override def route: Route = {
    get {
      path(wsPathMatcher) {
        promoteToWebSocket
      }
    } ~ siteRoute
  }

  override protected def initializeConnection (conn: WebSocketPushConnection): Unit = {
    fieldCatalog.foreach( pushTo(conn,_))
    providerList.foreach( pushTo(conn,_))
    providerData.foreach( e=> pushTo(conn,e._2))
  }
}
