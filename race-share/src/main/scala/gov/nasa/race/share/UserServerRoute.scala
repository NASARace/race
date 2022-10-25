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

package gov.nasa.race.share

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BatchedTimeoutMap, BufferedStringJsonPullParser, ByteSlice, JsonParseException, JsonSerializable, JsonWriter, TimeoutSubject}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.http._
import gov.nasa.race.uom.Time.Milliseconds
import gov.nasa.race.uom.{DateTime, Time}

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt


/**
  * the server route to communicate with node-local devices, which are used to display our data model and
  * generate CDCs to modify ColumnData that is owned by this node
  *
  * note this assumes we have a low (<100) number of clients or otherwise we should cache client initialization
  * messages. However, if we do so it will become harder to later-on add different client profiles. So far,
  * we assume all our clients will get the same data
  */
class UserServerRoute (parent: ParentActor, config: Config) extends AuthSiteRoute(parent,config)
                              with PushWSRaceRoute with AuthWSRaceRoute with PipedRaceDataClient {
  /**
    * what we need to keep track of EditRequests - userChange messages are only valid between a requestEdit and
    * endEdit, up to a configurable inactive timeout that is reset upon each userChange
    */
  case class EditSession (uid: String, clientAddr: InetSocketAddress, perms: Seq[CellSpec]) extends TimeoutSubject {
    val timeout: Time = editSessions.checkInterval - Milliseconds(50)

    def timeoutExpired(): Unit = {
      terminateEdit(clientAddr,"inactive edit session timeout")
      warning(s"edit session for user $uid on $clientAddr expired")
    }
  }

  /**
    * JSON parser for incoming client (device) messages
    * note that we directly execute respective actions instead of just translating JSON into scala
    */
  class IncomingMessageHandler extends BufferedStringJsonPullParser {
    //--- lexical constants
    private val REQUEST_EDIT = asc("requestEdit")
    private val USER_CHANGE = asc("userChange")
    private val END_EDIT = asc("endEdit")

    private val UID = asc("uid")
    private val COLUMN_ID = asc("columnId")
    private val DATE = asc("date")
    private val CHANGED_VALUES = asc("changedValues")

    def alertUser (clientAddr: InetSocketAddress, msg: String): Unit = UserServerRoute.this.alertUser(clientAddr,msg)

    //--- message processing

    def parseMessage(msg: String, ctx: AuthWSContext): Option[Iterable[Message]] = {
      val conn = ctx.sockConn
      parseMessage(msg) {
        case REQUEST_EDIT => Some(processRequestEdit(ctx,msg))  // this starts the authMethod protocol
        case USER_CHANGE => Some(processUserChange(conn.remoteAddress,msg))
        case END_EDIT => Some(processEndEdit(conn.remoteAddress,msg))
        case msgTag => processAuthMessage(conn, msgTag).orElse { info(s"ignoring unhandled client message '$msg'"); None }
      }
    }

    def processAuthMessage (conn: SocketConnection, msgTag: ByteSlice): Option[Iterable[Message]] = {
      authMethod.processJSONAuthMessage(conn, msgTag, this) match {
        case Some(AuthResponse.Challenge(msg,_)) =>
          Some( Seq(TextMessage(msg)))

        case Some(AuthResponse.Accept(uid,authAcceptMsg,_)) =>
          val clientAddr = conn.remoteAddress
          enableUserPermissions(clientAddr,uid) match {
            case Some(perms) => Some( Seq(TextMessage(authAcceptMsg), TextMessage(perms)))
            case None => Some( Seq(TextMessage(authMethod.rejectAuthMessage("insufficient user permissions"))))
          }

        case Some(AuthResponse.Reject(msg,_)) =>
          Some( Seq(TextMessage(msg)))

        case None => None
      }
    }

    def enableUserPermissions (clientAddr: InetSocketAddress, uid: String): Option[String] = {
      val perms = userPermissions.getPermissions(uid)
      if (perms.nonEmpty) {
        val es = EditSession(uid, clientAddr, perms)
        info(s"start edit $es")
        editSessions += (clientAddr -> es)
        Some( UserPermissions.serializePermissions(writer, uid, perms))
      } else None
    }

    /**
      * process requestEdit message
      * format: { "requestEdit": { "uid": "<userId>" } }
      * this might kick off user authentication in case this was not an authenticated route
      */
    def processRequestEdit(ctx: AuthWSContext, msg: String): Iterable[Message] = {
      val clientAddr = ctx.sockConn.remoteAddress

      try {
        editSessions.get(clientAddr) match {
          case Some(session) => // ignore, we already have an active edit session from this location

          case None =>
            val uid: String = quotedValue.toString
            if (!uid.isEmpty && !isValidUid(uid)) {
              alertUser(clientAddr, s"unknown user '$uid'")

            } else {
              sessions(ctx.sessionToken) match {
                case Some(e) =>
                  if (e.uid == uid) { // no need to ask for the credentials again
                    enableUserPermissions(clientAddr,uid) match {
                      case Some(perms) => pushTo(clientAddr, TextMessage(perms))
                      case None => pushTo(clientAddr, TextMessage("""{"alert":"insufficient user permissions"}"""))
                    }

                  } else { // edit session for different user - kick off aiuth
                    info(s"starting authentication for $clientAddr uid: '$uid'")
                    pushTo(clientAddr, TextMessage(authMethod.startAuthMessage(uid)))
                  }

                case None => // no login from this address?
                  info(s"starting authentication for $clientAddr uid: '$uid'")
                  pushTo(clientAddr, TextMessage(authMethod.startAuthMessage(uid)))
              }
            }
        }
      } catch {
        case x: JsonParseException => warning(s"ignoring malformed requestEdit message: '$msg'")
      }

      Nil
    }

    /**
      * process userChange message
      * format: {"userChange":{  "uid": "<userName>", "columnId": <string>, "date": <dt>, "changedValues":{ "<row-id>": <value>, .. }}
      * NOTE - we use our own time stamp for result objects since we can't rely on client machine clock sync
      */
    def processUserChange(clientAddr: InetSocketAddress, msg: String): Iterable[Message] = {
      def checkUserDate (date: DateTime, userDate: DateTime): Boolean = {
        true // TBD - compare if within eps of currentDateTime
      }

      def readChangedValues(date: DateTime): Seq[(String,CellValue[_])] = {
        val cvs = ArrayBuffer.empty[(String,CellValue[_])]

        foreachInCurrentObject {
          val rowId = member.intern

          getRow(rowId) match {
            case Some(row) =>
              val v = row.parseValueFrom(this)(date)
              cvs += (rowId -> v)
            case None => warning(s"skipping unknown field $rowId")
          }
        }
        cvs.toSeq
      }

      for (nid <- nodeId) {
        try {
          var uid: String = null
          var columnId: String = null
          var userDate = DateTime.UndefinedDateTime
          var changedValues = Seq.empty[(String,CellValue[_])]
          val date = currentDateTime // this is what we use for UC and CDC since we can only trust our clock

          foreachMemberInCurrentObject {
            case UID => uid = quotedValue.toString
            case COLUMN_ID => columnId = quotedValue.intern
            case DATE => userDate = dateTimeValue
            case CHANGED_VALUES => changedValues = readCurrentObject( readChangedValues(date) )
          }

          if (uid != null && changedValues.nonEmpty && checkUserDate(date, userDate)) {
            editSessions.get(clientAddr) match {
              case Some(es) =>
                if (uid == es.uid) {
                  es.resetExpiration() // reset timeout base
                  val permCvs = filterPermittedChanges(columnId, changedValues, es)
                  if (permCvs.nonEmpty) {
                    // TODO - this could also reject if any of the changes are not permitted
                    if (permCvs ne changedValues) {
                      warning(s"some changes rejected due to insufficient permissions: $msg")
                    }
                    publishData(ColumnDataChange(columnId, nid, date, permCvs))
                  } else warning(s"insufficient user permissions for $msg")
                } else warning(s"ignoring changes from non-authenticated user: $msg")

              case None =>
                warning(s"userChange without open editSession ($uid, $clientAddr)")
                return Seq(TextMessage("""{ "changeRejected": {"uid": "$uid", "reason": "no edit session"} }"""))
            }
          } else throw exception(s"incomplete userChange message: $msg")
        } catch {
          case x: JsonParseException => warning(s"ignoring malformed userChange: ${x.getMessage}")
        }
      }

      Nil // no response message
    }

    /**
      * process endEdit message
      * format: { "endEdit": { "uid": "<userName>" } }
      */
    def processEndEdit (clientAddr: InetSocketAddress, msg: String): Iterable[Message] = {
      try {
        var uid: String = null
        foreachMemberInCurrentObject {
          case UID => uid = quotedValue.toString
            //... maybe more in the future
        }

        if (uid != null) {
          editSessions.get(clientAddr) match {
            case Some(es) =>
              info(s"end $es")
              editSessions -= clientAddr
            case None =>
              warning(s"attempt to end non-existing edit session for user '$uid'")
          }
        } else throw exception (s"ignoring incomplete endEdit message: $msg")
      } catch {
        case x: JsonParseException => warning(s"ignoring malformed endEdit: ${x.getMessage}")
      }

      Nil
    }
  }

  val clampUser = config.getBooleanOrElse("clamp-user", true)
  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  val clientHandler = new IncomingMessageHandler // we can't parse until we have catalogs
  clientHandler.setLogging(info,warning,error)

  val writer = new JsonWriter
  val userPermissions: UserPermissions = readUserPermissions

  var node: Option[Node] = None // we get this from the UpdateActor upon initialization

  // a timeout checked map for (clientAddr -> EditSession) pairs
  val editSessions: BatchedTimeoutMap[InetSocketAddress,EditSession] = new BatchedTimeoutMap(config.getFiniteDurationOrElse("edit-timeout", 5.minutes))

  def readUserPermissions: UserPermissions = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse).getOrElse(noUserPermissions)
  }

  def activeUid (clientAddr: InetSocketAddress): Option[String] = {
    editSessions.get(clientAddr).map(_.uid)
  }

  def isValidUid (uid: String): Boolean = {
    userPermissions.isKnownUser(uid)
  }

  //--- various Node accessors
  def currentDateTime: DateTime = {
    if (node.isDefined) node.get.currentDateTime else DateTime.UndefinedDateTime
  }

  def getRow (rowId: String): Option[Row[_]] = for (n <- node; row <- n.rowList.get(rowId)) yield row

  def nodeId: Option[String] = node.map(_.id)

  //--- client message constructors

  def alertUser (clientAddr: InetSocketAddress, msg: String): Unit = {
    pushTo(clientAddr, TextMessage(s"""{"alert":{"text":"$msg"}}"""))
  }

  def terminateEdit (clientAddr: InetSocketAddress, reason: String): Unit = {
    pushTo(clientAddr, TextMessage(s"""{"terminateEdit":{"reason":"$reason"}}"""))
  }

  def getSessionUIDMessage (ctx: AuthWSContext): Option[Message] = {
    sessions(ctx.sessionToken).map( se=> TextMessage(s"""{"setUser":{"uid": "${se.uid}","clamped": $clampUser}}"""))
  }

  // we use short serialization without connectivity info and send/receive filters (browser client shouldn't know)

  def getNodeListMessage: Option[Message] = {
    node.map( n=> TextMessage(writer.clear().toJson( n.nodeList.shortSerializeTo)) )
  }

  def getColumnListMessage: Option[Message] = node.map( n=> TextMessage(writer.clear().toJson( n.columnList.shortSerializeTo)))

  def getRowListMessage: Option[Message] = node.map( n=> TextMessage(writer.clear().toJson( n.rowList.shortSerializeTo)))

  def getColumnDataMessages: Seq[Message] = {
    node match {
      case Some(n) =>
        val msgs = mutable.Buffer.empty[Message]
        n.foreachOrderedColumnData( cd=> msgs += TextMessage( writer.clear().toJson(cd)) )
        msgs.toSeq

      case None => Nil
    }
  }

  def getInitialUpstreamMessage: Option[Message] = {
    node.flatMap( n=> n.upstreamId.map( upId=> TextMessage(writer.toJson(UpstreamChange.online(upId)))))
  }

  /**
    * this is just a ConstraintChange message that only has a 'violated' part
    */
  def getInitialConstraintsMessage: Option[Message] = {
    node.flatMap( n=> if (n.hasConstraintViolations) Some(TextMessage(writer.toJson(n.currentConstraintViolations))) else None)
  }

  /**
    * this is an NRC with our current online nodes
    */
  def getInitialNodeReachabilityMessages: Seq[Message] = {
    node match {
      case Some(n) => Seq(TextMessage.Strict(writer.toJson( NodeReachabilityChange.online( n.currentDateTime, n.onlineNodes.toSeq))))
      case _ => Nil
    }
  }

  //--- RaceDataClient interface

  /**
    * this is what we get from our DataClient actor from the RACE bus
    * NOTE this is executed async - avoid data races
    */
  override def receiveData: Receive = {
    case BusEvent(_,n: Node,_) => node = Some(n)

      //--- those go directly out to connected clients
    case BusEvent(_,cdc: ColumnDataChange,_) => pushMsg(cdc)
    case BusEvent(_,cc: ConstraintChange,_) => pushMsg(cc)
    case BusEvent(_,nrc: NodeReachabilityChange,_) => pushMsg(nrc)
    case BusEvent(_,uc: UpstreamChange,_) => pushMsg(uc)
  }

  def pushMsg (o: JsonSerializable): Unit = synchronized {
    if (hasConnections) push(TextMessage(writer.clear().toJson(o)))
  }

  protected def filterPermittedChanges (columnId: String, cvs: Seq[(String,CellValue[_])], es: EditSession): Seq[(String,CellValue[_])] = {
    cvs.filter { cv=> es.perms.exists( p=> p.colRE.matches(columnId) && p.rowRE.matches(cv._1)) }
  }

  /**
    * this is what we get from user devices through their web sockets
    * note we don't delegate un-handled client messages to super types here
    */
  override protected def handleIncoming (ctx: WSContext, m: Message): Iterable[Message] = {
    withAuthWSContext(ctx) { authCtx =>
      val responseMsgs = m match {
        case tm: TextMessage.Strict =>
          clientHandler.parseMessage(tm.text, authCtx) match {
            case Some(replies) => replies
            case None => Nil // not handled
          }
        case _ => Nil // we don't process streams
      }
      discardMessage(m)
      responseMsgs
    }
  }

  override def route: Route = {
    get {
      path(wsPathMatcher) {
        promoteToWebSocket()
      }
    } ~ siteRoute
  }

  /**
    * we could cache the less likely changed messages (siteIds,CL,RL) but it is not clear if that buys much
    * in case there are frequent changes and a low number of isOnline clients
    */
  override protected def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val clientAddr = ctx.remoteAddress
    def pushMsg (msg: Option[Message]) = msg.foreach( pushTo(clientAddr, queue, _))
    def pushMsgs (msgs: Seq[Message]) = msgs.foreach( pushTo(clientAddr, queue, _))

    pushMsg( withAuthWSContext(ctx)( getSessionUIDMessage))
    pushMsg( getNodeListMessage)
    pushMsg( getColumnListMessage)
    pushMsg( getRowListMessage)

    pushMsgs(getColumnDataMessages)

    pushMsgs(getInitialNodeReachabilityMessages)
    pushMsg( getInitialUpstreamMessage)
    pushMsg( getInitialConstraintsMessage)
  }

  override def onRaceTerminated (server: HttpServer): Boolean = {
    editSessions.terminate()
    authMethod.shutdown() // the authenticator might have to close files etc
    true
  }
}
