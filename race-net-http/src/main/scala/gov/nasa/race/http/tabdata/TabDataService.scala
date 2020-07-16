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

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import com.typesafe.config.Config
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{DataConsumerRaceActor, ParentActor, RaceDataConsumer, RaceException}
import gov.nasa.race.http.tabdata.UserPermissions.{PermSpec, Perms}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute, WebSocketPushConnection}

import scala.collection.immutable.Iterable
import scala.collection.mutable.{Map => MutMap}

/**
  * the RaceRouteInfo that serves the content to display and update Provider data
  * a generic web application service that pushes provider data to connected clients through a web socket
  * and allows clients / authorized users to update this data through JSON messages. The data model consists of
  * three elements:
  *
  *   - field catalog: defines the ordered set of fields that can be recorded for each provider
  *   - provider catalog: defines the ordered set of providers for which data can be recorded
  *   - {provider-data}: maps from field ids to Int values for each provider
  */
class TabDataService (parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                                  with PushWSRaceRoute with RaceDataConsumer {

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  var parser: Option[IncomingMessageParser] = None // we can't parse until we have catalogs
  val writer = new JsonWriter

  var providerCatalog: Option[ProviderCatalog] = None
  var fieldCatalog: Option[FieldCatalog] = None

  // we store the data in a format that is ready to send
  var fieldCatalogMessage: Option[TextMessage] = None
  var providerCatalogMessage: Option[TextMessage] = None
  val providerDataMessage = MutMap.empty[String,TextMessage]

  val userPermissions: UserPermissions = readUserPermissions

  def readUserPermissions: UserPermissions = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse) match {
      case Some(userPermissions) => userPermissions
      case None => throw new RaceException("error parsing user-permissions")
    }
  }

  def serializeFieldCatalog (fieldCatalog: FieldCatalog): String = {
    fieldCatalog.serializeTo(writer)
    writer.toJson
  }

  def serializeProviderCatalog (providerCatalog: ProviderCatalog): String = {
    providerCatalog.serializeTo(writer)
    writer.toJson
  }

  def serializeProviderData (providerData: ProviderData): String = {
    writer.clear.writeObject( _
      .writeMemberObject("providerData") { _
        .writeStringMember("id", providerData.id)
        .writeIntMember("rev", providerData.rev)
        .writeLongMember("date", providerData.date.toEpochMillis)
        .writeMemberObject("fieldValues") { w =>
          providerData.fieldValues.foreach { fv =>
            w.writeMemberName(fv._1)
            w.writeUnQuotedValue(fv._2.toJson)
          }
        }
      }
    ).toJson
  }

  def serializeUserPermissions (user: String, perms: Perms): String = {
    writer.clear.writeObject( _
      .writeMemberObject( "userPermissions") { _
        .writeStringMember("uid", user)
        .writeStringMembersMember("permissions", perms)
      }
    ).toJson
  }

  override protected def instantiateActor: DataConsumerRaceActor = new TabDataServiceActor(this,config)

  protected def createIncomingMessageParser: Option[IncomingMessageParser] = {
    for ( fc <- fieldCatalog; pc <- providerCatalog) yield {
      val p = new IncomingMessageParser(fc,pc)
      p.setLogging(info,warning,error)
      p
    }
  }

  /**
    * this is called from our actor
    * NOTE this is executed async - avoid data races
    */
  override def setData (data:Any): Unit = {
    data match {
      case p: ProviderData =>
        val msg = TextMessage(serializeProviderData(p))
        providerDataMessage.put(p.id,msg)
        push(msg)

      case c: ProviderCatalog =>
        val msg = TextMessage(serializeProviderCatalog(c))
        providerCatalog = Some(c)
        providerCatalogMessage = Some(msg)
        parser = createIncomingMessageParser
        push(msg)

      case c: FieldCatalog =>
        val msg = TextMessage(serializeFieldCatalog(c))
        fieldCatalog = Some(c)
        fieldCatalogMessage = Some(msg)
        parser = createIncomingMessageParser
        push(msg)
    }
  }

  override protected def handleIncoming (m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parser match {
          case Some(p) =>
            p.parse(tm.text) match {
              case Some(RequestUserPermissions(uid,pw)) =>
                // TODO - pw ignored for now
                val perms = userPermissions.users.getOrElse(uid,Seq.empty[PermSpec])
                TextMessage(serializeUserPermissions(uid,perms)) :: Nil

              case Some(pc@ProviderChange(uid,pw,date,provider,fieldValues)) =>
                // TODO - check user credentials and permissions
                actorRef ! pc // we send the ProviderChange to our actor, which manages the data model and reports changes back for publishing
                Nil

              case _ =>
                warning(s"ignoring unknown incoming message ${tm.text}")
                Nil
            }
          case None =>
            warning("ignoring incoming message - no catalogs yet")
            Nil
        }


      case _ =>
        super.handleIncoming(m)
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
    fieldCatalogMessage.foreach( pushTo(conn,_))
    providerCatalogMessage.foreach( pushTo(conn,_))
    providerDataMessage.foreach(e=> pushTo(conn,e._2))
  }

}
