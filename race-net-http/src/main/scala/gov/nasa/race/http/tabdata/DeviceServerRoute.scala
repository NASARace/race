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

import java.net.InetSocketAddress

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonWriter}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentActor, RaceDataClient, RaceException}
import gov.nasa.race.http.tabdata.UserPermissions.{PermSpec, Perms}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.Iterable
import scala.collection.mutable.{ArrayBuffer, Map => MutMap}

/**
  * client request for user permissions specifying which fields can be edited for given user credentials
  */
case class RequestUserPermissions (uid: String, pw: Array[Byte])

case class UserChange(uid: String, pw: Array[Byte], change: ProviderDataChange)

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
class DeviceServerRoute(parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                           with PushWSRaceRoute with RaceDataClient {

  /**
    * JSON parser for incoming device messages
    */
  class IncomingMessageParser (site: Node) extends BufferedStringJsonPullParser {
    //--- lexical constants
    private val _date_ = asc("date")
    private val _requestUserPermissions_ = asc("requestUserPermissions")
    private val _providerCatalogId_ = asc("providerCatalogId")
    private val _fieldCatalogId_ = asc("fieldCatalogId")
    private val _siteId_ = asc("siteId")
    private val _userChange_ = asc("userChange")

    def parse (msg: String): Option[Any] = parseMessageSet(msg) {
      case `_requestUserPermissions_` => parseRequestUserPermissions(msg)
      case `_userChange_` => parseUserChange(msg)
      case m => warning(s"ignoring unknown message '$m''"); None
    }

    // {"requestUserPermissions":{ "<uid>":[byte..]}
    def parseRequestUserPermissions (msg: String): Option[RequestUserPermissions] = {
      val uid = readArrayMemberName().toString
      val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray

      Some(RequestUserPermissions(uid,pw))
    }

    // {"providerChange":{  "<uid>":[byte..], "date":<long>, "providerCatalogId": "s", "fieldCatalogId": "s", "siteId": "s", "<provider>":{ "<field>": <value>, .. }}
    def parseUserChange(msg: String): Option[UserChange] = {
      var fieldValues = Seq.empty[(String,FieldValue)]

      val uid = readArrayMemberName().toString
      val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray
      val date = DateTime.ofEpochMillis(readUnQuotedMember(_date_).toLong)
      val providerCatalogId = readQuotedMember(_providerCatalogId_).toString
      val fieldCatalogId = readQuotedMember(_fieldCatalogId_).toString
      val siteId = readQuotedMember(_siteId_).toString
      val providerId = readObjectMemberName().toString
      // TODO - we could check for valid providers here
      foreachInCurrentObject {
        val v = readUnQuotedValue()
        val fieldId = member.toString
        site.fieldCatalog.fields.get(fieldId) match {
          case Some(field) => fieldValues = fieldValues :+ (fieldId -> field.valueFrom(v)(date))
          case None => warning(s"skipping unknown field $fieldId")
        }
      }

      val pdc = ProviderDataChange(providerId,date,providerCatalogId,fieldCatalogId,siteId,fieldValues)
      Some(UserChange(uid,pw,pdc))
    }
  }

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  var parser: Option[IncomingMessageParser] = None // we can't parse until we have catalogs
  val writer = new JsonWriter

  var site: Option[Node] = None

  // cache for messages sent to devices
  var fieldCatalogMessage: Option[TextMessage] = None
  var providerCatalogMessage: Option[TextMessage] = None
  var siteIdMessage: Option[TextMessage] = None
  val providerDataMessages = MutMap.empty[String,TextMessage]

  val userPermissions: UserPermissions = readUserPermissions

  def readUserPermissions: UserPermissions = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse) match {
      case Some(userPermissions) => userPermissions
      case None => throw new RaceException("error parsing user-permissions")
    }
  }

  def serializeSiteId (id: String): String = {
    writer.clear.writeObject( _.writeStringMember("siteId", id)).toJson
  }

  def serializeUserPermissions (user: String, perms: Perms): String = {
    writer.clear.writeObject( _
      .writeMemberObject( "userPermissions") { _
        .writeStringMember("uid", user)
        .writeStringMembersMember("permissions", perms)
      }
    ).toJson
  }

  protected def createIncomingMessageParser: Option[IncomingMessageParser] = {
    site.map{ s=>
      val p = new IncomingMessageParser(s)
      p.setLogging(info,warning,error)
      p
    }
  }

  /**
    * this is what we get from our serviceActor
    * NOTE this is executed async - avoid data races
    */
  override def receiveData(data:Any): Unit = {
    data match {
      case s: Node =>  // our initial data
        site = Some(s)
        parser = createIncomingMessageParser
        siteIdMessage = Some( TextMessage(serializeSiteId(s.id)))
        fieldCatalogMessage = Some( TextMessage(writer.toJson(s.fieldCatalog)))
        providerCatalogMessage = Some( TextMessage(writer.toJson(s.providerCatalog)))
        info(s"device server '${s.id}' ready to accept connections")

      //--- data changes
      case fc: FieldCatalog =>
        site = site.map( _.copy(fieldCatalog=fc))
        parser = createIncomingMessageParser
        val msg = TextMessage(writer.toJson(fc))
        fieldCatalogMessage = Some(msg)
        push(msg)
        if (hasConnections) info(s"device server pushing new field catalog '${fc.id}'")

      case pc: ProviderCatalog =>
        site = site.map( _.copy(providerCatalog=pc))
        parser = createIncomingMessageParser
        val msg = TextMessage(writer.toJson(pc))
        providerCatalogMessage = Some(msg)
        push(msg)
        if (hasConnections) info(s"device server pushing new provider catalog '${pc.id}'")

      case pd: ProviderData =>
        val msg = TextMessage(writer.toJson(pd))
        providerDataMessages.put( pd.providerId, msg)
        push(msg)
        if (hasConnections) info(s"device server pushing new provider data for '${pd.providerId}'")

      case other => // ignore
    }
  }

  override protected def handleIncoming (remoteAddr: InetSocketAddress, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parser match {
          case Some(p) =>
            p.parse(tm.text) match {
              case Some(RequestUserPermissions(uid,pw)) =>
                // TODO - pw ignored for now
                val perms = userPermissions.users.getOrElse(uid,Seq.empty[PermSpec])
                TextMessage(serializeUserPermissions(uid,perms)) :: Nil

              case Some(pc@UserChange(uid,pw,pdc:ProviderDataChange)) =>
                // TODO - check user credentials and permissions
                publishData(pdc) // the service
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
        super.handleIncoming(remoteAddr, m)
    }
  }

  override def route: Route = {
    get {
      path(wsPathMatcher) {
        promoteToWebSocket
      }
    } ~ siteRoute
  }

  override protected def initializeConnection (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    siteIdMessage.foreach( pushTo(remoteAddr, queue ,_))
    fieldCatalogMessage.foreach( pushTo(remoteAddr, queue ,_))
    providerCatalogMessage.foreach( pushTo(remoteAddr, queue, _))
    providerDataMessages.foreach(e=> pushTo(remoteAddr, queue, e._2))
  }

}
