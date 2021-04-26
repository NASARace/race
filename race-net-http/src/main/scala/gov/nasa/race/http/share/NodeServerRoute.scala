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

import java.net.InetSocketAddress
import java.nio.file.Path
import akka.Done
import akka.actor.Actor.Receive
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{get, path}
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonParseException, JsonSerializable, JsonWriter, SyncJsonWriter}
import gov.nasa.race.core.{ContinuousTimeRaceActor, ParentActor, Ping, PingParser, Pong, RaceDataClient}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute, SocketConnection}
import gov.nasa.race.{ifSome, withSomeOrElse}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util.Try

object NodeServerRoute {
  val requestPrefix = "share-integrator"
}

/**
  * the route that handles ColumnData synchronization from/to child node clients through a websocket
  *
  * note that we don't register clients (child nodes) or send our NodeDates to them before they send us
  * their NodeDates (at (re-)start, after connecting). This is to ensure 〖 minimal network config 〗since the
  * server does not need to know client IP addresses a priori
  */
class NodeServerRoute(val parent: ParentActor, val config: Config)
                                        extends PushWSRaceRoute with RaceDataClient with NodeDatesResponder {

  /**
    * the parser for incoming (web socket) messages. Note this is from trusted/checked connections
    */
  class IncomingMessageParser (val node: Node) extends BufferedStringJsonPullParser
                                              with ColumnDataChangeParser with NodeDatesParser with PingParser {
    def rowList = node.rowList

    def parse(msg: String): Option[Any] = parseMessageSet[Option[Any]](msg,None) {
      case ColumnDataChange.COLUMN_DATA_CHANGE => parseColumnDataChange()
      case NodeDates.NODE_DATES => parseNodeDates()
      case Ping.PING => parsePing()
      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }

  val wsPath = requestPrefix
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  protected var parser: Option[IncomingMessageParser] = None  // we can't have one before we have a node

  // writers are not thread safe - we use separate ones for each thread to avoid blocking
  val dataClientWriter = new JsonWriter // to serialize from within receiveData() thread (Race actor)
  val wsWriter = new JsonWriter // to serialize from within handleIncoming() thread (websocket)

  var node: Option[Node] = None // our data
  var remoteNodes: Map[InetSocketAddress,String] = Map.empty

  override def getRequestPrefix = NodeServerRoute.requestPrefix  // it's hardwired

  /**
    * this is what we get from our RaceDataClient actor, i.e. what we receive from the RACE bus
    *
    * NOTE this is executed async - avoid data races
    */
  override def receiveData: Receive = {
    case newNode: Node =>
      node = Some(newNode)
      if (!parser.isDefined) {
        parser = Some(new IncomingMessageParser(newNode))
        info(s"node server '${newNode.id}' ready to accept connections")
      }
      // no need to send anything since we still get CDCs for changes and node sync is initiated by child nodes

    case cdc: ColumnDataChange => processColumnDataChange(cdc)

    case nrc: NodeReachabilityChange => processNodeReachabilityChange(nrc)
  }

  // TODO - this is not very efficient if we don't filter rows. We might move the filtering at least into the serializer
  def processColumnDataChange (cdc: ColumnDataChange): Unit = {
    ifSome(node) { node=>
      val targetId = cdc.columnId

      ifSome( node.columnList.get(targetId)) { col=>
        remoteNodes.foreach { e=>
          val (remoteAddr,remoteId) = e
          if (cdc.changeNodeId != remoteId) { // we don't send it back to where it came from
            if (col.isSentTo(remoteId)(node)) {
              val filteredCdc = cdc.filter { r=>
                node.rowList.get(r) match {
                  case Some(row) => row.isSentTo(remoteId)(node,col)
                  case None => false // unknown row
                }
              }
              if (filteredCdc.nonEmpty) {
                val m = TextMessage.Strict(dataClientWriter.toJson(filteredCdc))
                pushTo(remoteAddr, m)
              }
            }
          }
        }
      }
    }
  }

  def processNodeReachabilityChange (nrc: NodeReachabilityChange): Unit = {
    for (n <- node; nrc <- n.getDownstreamReachabilityChange(nrc)) {
      val m = TextMessage.Strict(dataClientWriter.toJson(nrc))

      remoteNodes.foreach { e =>
        val (remoteAddr, remoteId) = e
        if (!nrc.isSingleOnlineChange(remoteId)) { // don't send it back to the node that just got online
          pushTo(remoteAddr, m)
        }
      }
    }
  }

  protected def parseIncoming (tm: TextMessage.Strict): Option[Any] = parser.flatMap( _.parse(tm.text))
  protected def isKnownColumn (id: String): Boolean = node.isDefined && node.get.columnList.columns.contains(id)
  protected def isLocal (id: String): Boolean = node.isDefined && node.get.isOwnNode(id)
  protected def isUpstream (id: String): Boolean = node.isDefined && node.get.isUpstreamId(id)

  override protected def handleConnectionLoss (remoteAddress: InetSocketAddress, cause: Any): Unit = {
    remoteNodes.get(remoteAddress) match {
      case Some(remoteNodeId) =>
        ifSome(node) { node=>
          publishData( NodeReachabilityChange.offline(node.currentDateTime, remoteNodeId))
          remoteNodes = remoteNodes - remoteAddress
        }

      case None =>
        warning(s"ignoring connection loss to un-registered remote address: $remoteAddress")
    }

    super.handleConnectionLoss(remoteAddress,cause) // call this no matter what since super type does its own cleanup
  }

  protected def isNodeReachable (id: String): Boolean = {
    // TODO - we might turn this into a set for O(1) lookup
    remoteNodes.exists( e=> e._2 == id)
  }

  /**
    * this is what we receive through the websocket (from connected providers)
    * BEWARE - this is executed in a different (akka-http) thread. Use wsWriter for sync responses
    */
  override protected def handleIncoming (conn: SocketConnection, m: Message): Iterable[Message] = {
    val remoteAddr = conn.remoteAddress

    m match {
      case tm: TextMessage.Strict =>
        parseIncoming(tm) match {
          case Some(ns: NodeDates) => handleNodeDates(remoteAddr, ns)
          case Some(cdc: ColumnDataChange) => handleColumnDataChange(remoteAddr, tm, cdc)
          case Some(ping: Ping) => handlePing(ping, remoteAddr)
          case _ => Nil // ignore all other
        }

      case _ => super.handleIncoming(conn, m)
    }
  }

  /**
    * a downstream node sent us its NodeDates - register, send newer changes and own (filtered) NodeDates back
    *
    * once we get this we know the downStreamNode is isOnline. We can't use the websocket connection for this since
    * that only gives us the clientAddr but not the id
    */
  def handleNodeDates (remoteAddr: InetSocketAddress, nd: NodeDates): Iterable[Message] = {
    for (node <- node) {
      val remoteNodeId = nd.nodeId
      node.nodeList.downstreamNodes.get(remoteNodeId) match {
        case Some(nodeInfo) =>
          if (nodeInfo.addrMask.matchesInetAddress(remoteAddr.getAddress)) {
            val date = node.currentDateTime
            remoteNodes = remoteNodes + (remoteAddr -> remoteNodeId)  // assoc should not change until connection is lost

            val replies = ArrayBuffer.empty[JsonSerializable]
            replies ++= getColumnDataChanges( nd, node)
            replies += NodeReachabilityChange.online( date, node.onlineDownstreamIds) // tell downstream about its online peers
            replies += node.nodeDatesFor(remoteNodeId, date)

            publishData( NodeReachabilityChange.online(date, remoteNodeId))

            return replies.toSeq.map(o=> TextMessage.Strict(wsWriter.toJson(o)))

          } else warning(s"ignoring nodeDates from downstream node $remoteNodeId with invalid address $remoteAddr")
        case None => warning(s"ignoring nodeDates from unknown downstream node $remoteNodeId")
      }
    }
    Nil
  }

  def handleColumnDataChange (remoteAddr: InetSocketAddress, m: Message, cdc: ColumnDataChange): Iterable[Message] = {
    for (node <- node) {
      remoteNodes.get(remoteAddr) match {
        case Some(senderNodeId) =>
          //if (senderNodeId == cdc.changeNodeId) {
            node.columnList.get(cdc.columnId) match {
              case Some(col) =>
                if (col.isReceivedFrom(senderNodeId)(node)) {
                  val filteredCdc = cdc.filter { r =>
                    node.rowList.get(r) match {
                      case Some(row) => row.isReceivedFrom(senderNodeId)(node,col)
                      case None => false // unknown row
                    }
                  }
                  if (filteredCdc.nonEmpty) publishData(filteredCdc)
                }
              case None => warning(s"change for unknown column ${cdc.columnId} ignored")
            }
          //} else warning(s"sender is not originator")
        case None => warning(s"ignoring change from unknown sender $remoteAddr: $cdc")
      }
    }
    Nil // we don't reply anything here
  }

  def isOutdatedChange (cdc: ColumnDataChange): Boolean = {
    withSomeOrElse (node, false) { node =>
      node.columnDatas.get(cdc.columnId) match {
        case Some(cd) =>  cd.date >= cdc.date
        case None => false // we don't have ColumnData for this yet
      }
    }
  }

  def handlePing (ping: Ping, remoteAddr: InetSocketAddress): Iterable[Message] = {
    withSomeOrElse(node, Seq.empty[Message]){ node=>
      withSomeOrElse(remoteNodes.get(remoteAddr),Seq.empty[Message]){ remoteId=>
        if (remoteId == ping.sender && node.id == ping.receiver) {
          info(s"responding to ping from $remoteId")
          val pongTime = DateTime.now // this is wall clock, not simTime
          val pongMsg = TextMessage.Strict(wsWriter.toJson(Pong(pongTime,ping)))
          Seq(pongMsg)
        } else {
          warning(s"wrong ping ignored: $ping")
          Nil
        }
      }
    }
  }

  override def route: Route = {
    get {
      path(wsPathMatcher) {
        if (node.isDefined) {
          promoteToWebSocket
        } else {
          complete(StatusCodes.PreconditionFailed, "server not yet initialized")
        }
      }
    }
  }
}

