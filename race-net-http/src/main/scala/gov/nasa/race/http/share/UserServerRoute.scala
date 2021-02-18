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

package gov.nasa.race.http.share

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonWriter, PathIdentifier}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentActor, Ping, Pong, PongParser, RaceDataClient}
import gov.nasa.race.http.share.UserPermissions.{PermSpec, Perms}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.{ifSome, withSomeOrElse}

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable
import scala.collection.mutable.{ArrayBuffer, Map => MutMap}

/**
  * client request for user permissions specifying which fields can be edited for given user credentials
  */
case class RequestUserPermissions (uid: String, pw: Array[Byte])

case class UserChange(uid: String, pw: Array[Byte], change: ColumnDataChange)

/**
  * the server route to communicate with node-local devices, which are used to display our data model and
  * generate CDCs to modify ColumnData that is owned by this node
  */
class UserServerRoute (parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                            with PushWSRaceRoute with RaceDataClient {

  /**
    * JSON parser for incoming device messages
    */
  class IncomingMessageParser (node: Node) extends BufferedStringJsonPullParser with PongParser {
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
      case Ping._pong_ => parsePong(msg)
      case m => warning(s"ignoring unknown message '$m''"); None
    }

    // {"requestUserPermissions":{ "<uid>":[byte..]}
    def parseRequestUserPermissions (msg: String): Option[RequestUserPermissions] = {
      val uid = readArrayMemberName().toString
      val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray

      Some(RequestUserPermissions(uid,pw))
    }

    // parse user changed column data
    // {"userChange":{  "<uid>":[byte..], "date":<long>, "columnListId":<s>, "rowListId":<s> , "<column-id>":{ "<row-id>": <value>, .. }}
    def parseUserChange(msg: String): Option[UserChange] = {
      var cellValues = Seq.empty[(String,CellValue[_])]

      val uid = readArrayMemberName().toString
      val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray
      implicit val date = DateTime.ofEpochMillis(readUnQuotedMember(_date_).toLong)

      val columnListId = readQuotedMember(_columnListId_).intern
      val rowListId =    readQuotedMember(_rowListId_).intern

      val columnId =     PathIdentifier.resolve( readObjectMemberName().intern, columnListId)

      // TODO - check for valid column and user permissions

      foreachInCurrentObject {
        val rowId = PathIdentifier.resolve( readMemberName().intern, rowListId)

        node.rowList.get(rowId) match {
          case Some(row) =>
            val v = row.parseValueFrom(this)
            cellValues = cellValues :+ (rowId -> v)
          case None => warning(s"skipping unknown field $rowId")
        }
      }

      val cdc = ColumnDataChange(columnId,node.id,date,cellValues)
      Some(UserChange(uid,pw,cdc))
    }

    def parsePong (msg: String): Option[Pong] = {
      parsePongBody
    }
  }

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  var parser: Option[IncomingMessageParser] = None // we can't parse until we have catalogs
  val writer = new JsonWriter

  var node: Option[Node] = None // we get this from the UpdateActor upon initialization

  // cache for messages sent to devices
  var rowListMessage: Option[TextMessage] = None
  var columnListMessage: Option[TextMessage] = None
  var siteIdMessage: Option[TextMessage] = None
  val columnDataMessages = MutMap.empty[String,TextMessage]
  var constraintMessage: Option[TextMessage] = None

  val userPermissions: Option[UserPermissions] = readUserPermissions

  def readUserPermissions: Option[UserPermissions] = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse)
  }

  def serializeSiteId (id: String): String = {
    writer.clear().writeObject( _.writeStringMember("siteId", id.toString)).toJson
  }

  def serializeUserPermissions (user: String, perms: Perms): String = {
    writer.clear().writeObject( _
      .writeMemberObject( "userPermissions") { _
        .writeStringMember("uid", user)
        .writeStringMembersMember("permissions", perms)
      }
    ).toJson
  }

  protected def createIncomingMessageParser: Option[IncomingMessageParser] = {
    node.map{ s=>
      val p = new IncomingMessageParser(s)
      p.setLogging(info,warning,error)
      p
    }
  }

  //--- RaceDataClient interface

  /**
    * this is what we get from our DataClient actor from the RACE bus
    * NOTE this is executed async - avoid data races
    */
  override def receiveData(data:Any): Unit = {
    data match {
      case n: Node =>  // this is for our internal purposes
        node = Some(n)

        if (!parser.isDefined) { // one time initialization
          parser = createIncomingMessageParser
          siteIdMessage = Some(TextMessage(serializeSiteId(n.id)))
          rowListMessage = Some(TextMessage(writer.toJson(n.rowList)))
          columnListMessage = Some(TextMessage(writer.toJson(n.columnList)))

          info(s"device server '${n.id}' ready to accept connections")
        }

        constraintMessage = generateConstraintMessage(n)
        n.columnDatas.foreach { e=>
          columnDataMessages.put(e._1, TextMessage(writer.toJson(e._2)))
        }

      case cdc: ColumnDataChange =>
        // we should already have gotten the respective Node message
        ifSome(node) { n=>
          val msg = TextMessage(writer.toJson(cdc))
          if (hasConnections) push(msg)

          /*
          n.columnDatas.get(cdc.columnId) match {
            case Some(cd) =>
              val msg = TextMessage(writer.toJson(cd))
              columnDataMessages.put( cd.id, msg)
              if (hasConnections) {
                info(s"user server pushing new column data for '${cd.id}'")
                push(msg)
              }
            case None =>
              warning(s"unknown column for CDC: ${cdc.columnId}")
          }
           */
        }

      case cc: ConstraintChange =>
        ifSome(node) { n=>
          val msg = TextMessage(writer.toJson(cc))
          if (hasConnections) push(msg)
        }

      case other => // ignore other messages
    }
  }

  def generateConstraintMessage (n: Node): Option[TextMessage] = {
    if (n.hasConstraintViolations) {
      val msg = writer.clear().writeObject { _
        .writeMemberObject("violatedConstraints") { w=>
          n.foreachConstraintViolation( _.serializeAsMemberObjectTo(w) )
        }
      }.toJson
      Some(TextMessage(msg))
    } else None
  }

  protected def isAcceptChange(cdc: ColumnDataChange): Boolean = {
    withSomeOrElse( node, false) { site=>
      val nodeId = site.id
      val targetId = cdc.columnId

      withSomeOrElse( site.columnList.get(targetId),false) { col=>
        // sender counts as ourselves since each node is responsible for devices it serves
        col.updateFilter.receiveFromDevice(nodeId,targetId)
      }
    }
  }

  /**
    * this is what we get from user devices through their web sockets
    */
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

              case Some(pong:Pong) =>
                info(s"got pong from $remoteAddr: $pong")
                //handlePong(remoteAddr,pong)  // user level ping/pong does not seem to work
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
    constraintMessage.foreach( pushTo(remoteAddr, queue, _))
  }

}
