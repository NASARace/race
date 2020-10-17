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
import java.nio.file.Path

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonWriter, UnixPath}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{PROVIDER_CHANNEL, ParentActor, RaceDataClient, RaceException}
import gov.nasa.race.http.tabdata.UserPermissions.{PermSpec, Perms}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute}
import gov.nasa.race.{ifSome, withSomeOrElse}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.Iterable
import scala.collection.mutable.{ArrayBuffer, Map => MutMap}

/**
  * client request for user permissions specifying which fields can be edited for given user credentials
  */
case class RequestUserPermissions (uid: String, pw: Array[Byte])

case class UserChange(uid: String, pw: Array[Byte], change: ColumnDataChange)

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
class UserServerRoute(parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                           with PushWSRaceRoute with RaceDataClient {
  /**
    * JSON parser for incoming device messages
    */
  class IncomingMessageParser (site: Node) extends BufferedStringJsonPullParser {
    //--- lexical constants
    private val _date_ = asc("date")
    private val _requestUserPermissions_ = asc("requestUserPermissions")
    private val _columnListId_ = asc("columnListId")
    private val _rowListId_ = asc("rowListId")
    private val _nodeId_ = asc("nodeId")
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
      var cellValues = Seq.empty[(Path,CellValue)]

      val uid = readArrayMemberName().toString
      val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray
      implicit val date = DateTime.ofEpochMillis(readUnQuotedMember(_date_).toLong)

      val columnListId = UnixPath.intern(readQuotedMember(_columnListId_))
      val rowListId = UnixPath.intern(readQuotedMember(_rowListId_))
      val nodeId = UnixPath.intern(readQuotedMember(_nodeId_))
      val columnId = UnixPath.intern(readObjectMemberName())
      // TODO - we could check for valid providers here

      foreachInCurrentObject {
        val rowId = UnixPath.intern(readMemberName())
        site.rowList.get(rowId) match {
          case Some(row) =>
            val v = row.parseValueFrom(this)
            cellValues = cellValues :+ (rowId -> v)
          case None => warning(s"skipping unknown field $rowId")
        }
      }

      val cdc = ColumnDataChange(columnId,nodeId,date,cellValues)
      Some(UserChange(uid,pw,cdc))
    }
  }

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  var parser: Option[IncomingMessageParser] = None // we can't parse until we have catalogs
  val writer = new JsonWriter

  var site: Option[Node] = None

  // cache for messages sent to devices
  var rowListMessage: Option[TextMessage] = None
  var columnListMessage: Option[TextMessage] = None
  var siteIdMessage: Option[TextMessage] = None
  val columnDataMessages = MutMap.empty[Path,TextMessage]

  val userPermissions: Option[UserPermissions] = readUserPermissions

  def readUserPermissions: Option[UserPermissions] = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse)
  }

  def serializeSiteId (id: Path): String = {
    writer.clear.writeObject( _.writeStringMember("siteId", id.toString)).toJson
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
        rowListMessage = Some( TextMessage(writer.toJson(s.rowList)))
        columnListMessage = Some( TextMessage(writer.toJson(s.columnList)))
        info(s"device server '${s.id}' ready to accept connections")

      //--- data changes
      case rl: RowList =>
        site = site.map( _.copy(rowList=rl))
        parser = createIncomingMessageParser
        val msg = TextMessage(writer.toJson(rl))
        rowListMessage = Some(msg)
        push(msg)
        if (hasConnections) info(s"device server pushing new field catalog '${rl.id}'")

      case cl: ColumnList =>
        site = site.map( _.copy(columnList=cl))
        parser = createIncomingMessageParser
        val msg = TextMessage(writer.toJson(cl))
        columnListMessage = Some(msg)
        push(msg)
        if (hasConnections) info(s"device server pushing new provider catalog '${cl.id}'")

      case cd: ColumnData =>
        val msg = TextMessage(writer.toJson(cd))
        columnDataMessages.put( cd.id, msg)
        push(msg)
        if (hasConnections) info(s"device server pushing new provider data for '${cd.id}'")

      case other => // ignore
    }
  }

  protected def isAcceptChange(cdc: ColumnDataChange): Boolean = {
    withSomeOrElse( site, false) { site=>
      val nodeId = site.id
      val targetId = cdc.columnId

      withSomeOrElse( site.columnList.get(targetId),false) { col=>
        // sender counts as ourselves since each node is responsible for devices it serves
        col.updateFilter.receiveFromDevice(nodeId,targetId)
      }
    }
  }

  override protected def handleIncoming (remoteAddr: InetSocketAddress, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parser match {
          case Some(p) =>
            p.parse(tm.text) match {
              case Some(RequestUserPermissions(uid,pw)) =>
                userPermissions match {
                  case Some(userPerms) =>
                    // TODO - pw ignored for now
                    val perms = userPerms.users.getOrElse(uid,Seq.empty[PermSpec])
                    TextMessage(serializeUserPermissions(uid,perms)) :: Nil
                  case None =>
                    warning("no user-permissions set, editing rejected")
                    Nil
                }

              case Some(pc@UserChange(uid,pw,cdc:ColumnDataChange)) =>
                // TODO - check user credentials and permissions
                if (isAcceptChange(cdc)) {
                  publishData(cdc) // the service
                } else {
                  warning(s"incoming CDC rejected by column filter ${tm.text}")
                }
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
    rowListMessage.foreach( pushTo(remoteAddr, queue ,_))
    columnListMessage.foreach( pushTo(remoteAddr, queue, _))
    columnDataMessages.foreach(e=> pushTo(remoteAddr, queue, e._2))
  }

}
