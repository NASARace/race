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
package gov.nasa.race.http.tabdata

import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.PathMatchers
import com.typesafe.config.Config
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceDataClient, SubscribingRaceActor}
import gov.nasa.race.http.{PushWSRaceRoute, SubscribingRaceRoute}


/**
  * the invariant data shared by all TabDataRoute instances
  *
  * this is mostly an optimization construct since there can be a large number of device- and provider- connections
  * which would otherwise cause a lot of redundant data/processing, which could also potentially lead to inconsistencies
  * between devices and provider routes
  *
  * NOTE - to avoid data races all stored objects have to be invariant and there should not be any
  * requirement to synchronize sets of updates (i.e. no potential for high level data races).
  */
object TabDataRoute {

  //--- message caches
  var siteIdMessage: Option[TextMessage] = None
  var fieldCatalogMessage: Option[TextMessage] = None
  var providerCatalogMessage: Option[TextMessage] = None
  var siteDatesMessage: Option[TextMessage] = None
  var providerDataMessages = Map.empty[String,TextMessage]
}

/**
  * the update actor for websocket messages that are shared by TabDataRoutes
  *
  * this acts as an in-line processor that creates the required shared (cached) data and then passes down
  * all received messages to its TabDataRoute subscribers, to make sure caches are updated before subscribers
  * are notified
  */
class MessageSerializer (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {
  import TabDataRoute._

  val writer = new JsonWriter

  override def handleMessage: Receive = {
    case e@BusEvent(_,site: Node,_) =>
      siteIdMessage = Some(TextMessage(serializeSiteId(site.id)))
      fieldCatalogMessage = Some(TextMessage(writer.toJson(site.fieldCatalog)))
      providerCatalogMessage = Some(TextMessage(writer.toJson(site.providerCatalog)))
      publishBusEvent(e)

    case BusEvent(_,fc: FieldCatalog,_) =>
      fieldCatalogMessage = Some(TextMessage(writer.toJson(fc)))

    case BusEvent(_,pc: ProviderCatalog,_) =>
      providerCatalogMessage = Some(TextMessage(writer.toJson(pc)))

    case BusEvent(_,pd: ProviderData,_) =>
      providerDataMessages = providerDataMessages + (pd.providerId -> TextMessage(writer.toJson(pd)))

    case BusEvent(_,sd: NodeState,_) =>

  }

  def serializeSiteId (id: String): String = {
    writer.clear.writeObject( _
      .writeStringMember("siteId", id)
    ).toJson
  }
}


/**
  * common parts of Device- and ServerRoute
  */
trait TabDataRoute extends PushWSRaceRoute with RaceDataClient {

  val wsPath: String // to be defined by concrete type
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  val writer = new JsonWriter

  var site: Option[Node] = None


}
