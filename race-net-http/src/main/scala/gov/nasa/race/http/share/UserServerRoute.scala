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
import gov.nasa.race.{Failure, Result, Success}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonParseException, JsonSerializable, JsonWriter}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentActor, RaceDataClient}
import gov.nasa.race.http.{AuthClient, Authenticator, NoAuthenticator, PushWSRaceRoute, SiteRoute}
import gov.nasa.race.http.webauthn.WebAuthnAuthenticator
import gov.nasa.race.uom.DateTime

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


/**
  * the server route to communicate with node-local devices, which are used to display our data model and
  * generate CDCs to modify ColumnData that is owned by this node
  *
  * note this assumes we have a low (<100) number of clients or otherwise we should cache client initialization
  * messages. However, if we do so it will become harder to later-on add different client profiles. So far,
  * we assume all our clients will get the same data
  */
class UserServerRoute (parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                            with PushWSRaceRoute with RaceDataClient {
  /**
    * what we need to keep track of EditRequests
    */
  case class EditSession (uid: String, clientAddr: InetSocketAddress, perms: Seq[CellSpec])

  /**
    * JSON parser for incoming device messages
    * note that we directly execute respective actions instead of just translating JSON into scala
    */
  class IncomingMessageHandler extends BufferedStringJsonPullParser with AuthClient {
    //--- lexical constants
    private val REQUEST_EDIT = asc("requestEdit")
    private val USER_CHANGE = asc("userChange")
    private val END_EDIT = asc("endEdit")

    private val UID = asc("uid")
    private val COLUMN_ID = asc("columnId")
    private val DATE = asc("date")
    private val CHANGED_VALUES = asc("changedValues")

    setLogging(info,warning,error)  // delegate this to our enclosing RouteInfo

    //--- AuthClient interface

    // we don't discriminate between registration and authentication - a successful registration is
    // considered to be a valid authentication
    def completeIdentification (uid: String, clientAddr: InetSocketAddress, res: Result): Unit = {
      res match {
        case Success =>
          val perms = userPermissions.getPermissions(uid)
          if (perms.nonEmpty) {
            val es = EditSession(uid,clientAddr,perms)
            info(s"start $es")
            editSessions += (clientAddr -> es)
          }
          pushTo( clientAddr, TextMessage(UserPermissions.serializePermissions(writer, uid, perms)))

        case Failure(msg) => warning(s"user identification failed: $msg")
      }
    }

    override def sendRegistrationRequest (clientAddr: InetSocketAddress, msg: String): Unit = {
      val wrappedMsg = s"""{"publicKeyCredentialCreationOptions": $msg}"""
      pushTo(clientAddr, TextMessage(wrappedMsg))
    }

    override def completeRegistration (uid: String, clientAddr: InetSocketAddress, result: Result): Unit = {
      completeIdentification(uid, clientAddr, result)
    }

    override def sendAuthenticationRequest (clientAddr: InetSocketAddress, msg: String): Unit = {
      val wrappedMsg = s"""{"publicKeyCredentialRequestOptions": $msg}"""
      pushTo(clientAddr, TextMessage(wrappedMsg))
    }

    override def completeAuthentication (uid: String, clientAddr: InetSocketAddress, result: Result): Unit = {
      completeIdentification(uid, clientAddr, result)
    }

    //--- message processing

    def process (clientAddr: InetSocketAddress, msg: String): Iterable[Message] = parseMessageSet[Iterable[Message]](msg, Nil) {
      case REQUEST_EDIT => processRequestEdit(clientAddr,msg)
      case USER_CHANGE => processUserChange(clientAddr,msg)
      case END_EDIT => processEndEdit(clientAddr,msg)
      case m =>
        if (!authenticator.processClientMessage(clientAddr,msg)) warning(s"ignoring unknown message '$m''")
        Nil
    }

    /**
      * process requestEdit message
      * format: { "requestEdit": { "uid": "<userId>" } }
      * this might kick off user authentication in case this was not an authenticated route
      */
    def processRequestEdit(clientAddr: InetSocketAddress, msg: String): Iterable[Message] = {
      try {
        var uid: String = null
        foreachMemberInCurrentObject {
          case UID => uid = quotedValue.toString
        }

        if (uid != null) {
          editSessions.get(clientAddr) match {
            case Some(session) => // ignore, we already hava an active edit session from this location
            case None => authenticator.identifyUser( uid, clientAddr, this)
          }

        } else {
          warning(s"incomplete requestEdit message, missing uid: $msg")
        }
      } catch {
        case x: JsonParseException => warning(s"ignoring malformed requestEdit message: ${x.getMessage}")
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

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  val authenticator: Authenticator = getConfigurableOrElse[Authenticator]("authenticator")(new WebAuthnAuthenticator) // (NoAuthenticator)

  val clientHandler = new IncomingMessageHandler // we can't parse until we have catalogs
  clientHandler.setLogging(info,warning,error)

  val writer = new JsonWriter
  val userPermissions: UserPermissions = readUserPermissions

  var node: Option[Node] = None // we get this from the UpdateActor upon initialization

  val editSessions: mutable.Map[InetSocketAddress,EditSession] = mutable.Map.empty  // clientAddr -> editSession

  def readUserPermissions: UserPermissions = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse).getOrElse(noUserPermissions)
  }

  def activeUid (clientAddr: InetSocketAddress): Option[String] = {
    None // TODO - get from potential auth route connect
  }

  //--- various Node accessors
  def currentDateTime: DateTime = {
    if (node.isDefined) node.get.currentDateTime else DateTime.UndefinedDateTime
  }

  def getRow (rowId: String): Option[Row[_]] = for (n <- node; row <- n.rowList.get(rowId)) yield row

  def nodeId: Option[String] = node.map(_.id)

  //--- client message constructors

  def getSiteIdsMessage (clientAddr: InetSocketAddress): Option[Message] = {
    node.map { n=>
      val msg = writer.clear().writeObject { _
        .writeObject("siteIds") { w=>
          w.writeStringMember("nodeId", n.id)
          for (upId <- n.upstreamId) w.writeStringMember("upstreamId", upId)
          for (uid <- activeUid(clientAddr)) w.writeStringMember("uid", uid)
        }
      }.toJson
      TextMessage(msg)
    }
  }

  def getColumnListMessage: Option[Message] = node.map( n=> TextMessage(writer.clear().toJson(n.columnList)))

  def getRowListMessage: Option[Message] = node.map( n=> TextMessage(writer.clear().toJson(n.rowList)))

  def getColumnDataMessages: Seq[Message] = {
    node match {
      case Some(n) =>
        val msgs = mutable.Buffer.empty[Message]
        n.foreachOrderedColumnData( cd=> msgs += TextMessage( writer.clear().toJson(cd)) )
        msgs.toSeq

      case None => Nil
    }
  }

  /**
    * this is just a ConstraintChange message with only a 'violated' part for the ones that are
    */
  def getConstraintsMessage: Option[Message] = {
    node match {
      case Some(n) if n.hasConstraintViolations => Some(TextMessage(writer.clear().toJson(n.currentConstraintViolations)))
      case _ => None
    }
  }

  /**
    * we report column reachability so that clients do not have to map nodes to columns
    * to initialize we just send a CRC with the current online columns
    */
  def getReachabilityMessage: Option[Message] = {
    node match {
      case Some(n) =>
        val onlineColumnIds = n.onlineColumnIds
        if (onlineColumnIds.nonEmpty) {
          val crc = ColumnReachabilityChange.online( n.id, currentDateTime, onlineColumnIds)
          Some(TextMessage(writer.clear().toJson(crc)))
        } else None
      case _ => None
    }
  }

  //--- RaceDataClient interface

  /**
    * this is what we get from our DataClient actor from the RACE bus
    * NOTE this is executed async - avoid data races
    */
  override def receiveData(data:Any): Unit = {
    data match {
      case n: Node => node = Some(n) // this is just our internal data

        //--- those go directly out to connected clients
      case cdc: ColumnDataChange => pushMsg(cdc)
      case cc: ConstraintChange => pushMsg(cc)
      case nrc: NodeReachabilityChange => pushMsg(nrc)
      case crc: ColumnReachabilityChange => pushMsg(crc)

      case _ => // ignore other messages
    }
  }

  def pushMsg (o: JsonSerializable): Unit = synchronized {
    if (hasConnections) push(TextMessage(writer.clear().toJson(o)))
  }

  protected def filterPermittedChanges (columnId: String, cvs: Seq[(String,CellValue[_])], es: EditSession): Seq[(String,CellValue[_])] = {
    cvs.filter { cv=> es.perms.exists( p=> p.colRE.matches(columnId) && p.rowRE.matches(cv._1)) }
  }

  /**
    * this is what we get from user devices through their web sockets
    */
  override protected def handleIncoming (clientAddr: InetSocketAddress, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict => clientHandler.process(clientAddr,tm.text)
      case _ => Nil
    }
  }

  override def route: Route = {
    get {
      path(wsPathMatcher) {
        promoteToWebSocket
      }
    } ~ siteRoute
  }

  /**
    * we could cache the less likely changed messages (siteIds,CL,RL) but it is not clear if that buys much
    * in case there are frequent changes and a low number of isOnline clients
    */
  override protected def initializeConnection (clientAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    getSiteIdsMessage(clientAddr).foreach( pushTo(clientAddr, queue ,_))
    getColumnListMessage.foreach( pushTo(clientAddr, queue, _))
    getRowListMessage.foreach( pushTo(clientAddr, queue ,_))
    getColumnDataMessages.foreach( pushTo(clientAddr, queue, _))
    getConstraintsMessage.foreach( pushTo(clientAddr, queue, _))
    getReachabilityMessage.foreach( pushTo(clientAddr, queue, _))
  }

}
