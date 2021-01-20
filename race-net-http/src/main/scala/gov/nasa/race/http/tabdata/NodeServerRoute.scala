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

import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{get, path}
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonParseException, JsonWriter, SyncJsonWriter}
import gov.nasa.race.core.{ContinuousTimeRaceActor, ParentActor, RaceDataClient}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute}
import gov.nasa.race.{ifSome, withSomeOrElse}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/**
  * the route that handles ColumnData synchronization from/to child node clients through a websocket
  *
  * note that we don't register clients (child nodes) or send our NodeState to them before they send us
  * their NodeState (at (re-)start, after connecting). This is to ensure 〖 minimal network config 〗since the
  * server does not need to know client IP addresses a priori
  */
class NodeServerRoute(val parent: ParentActor, val config: Config) extends PushWSRaceRoute with RaceDataClient {

  /**
    * the parser for incoming messages. Note this is from trusted/checked connections
    */
  class IncomingMessageParser (val node: Node) extends BufferedStringJsonPullParser
                                              with ColumnDataChangeParser with NodeStateParser with PingParser {
    def rowList = node.rowList

    def parse(msg: String): Option[Any] = parseMessageSet(msg) {
      case ColumnDataChange._columnDataChange_ => parseColumnDataChangeBody
      case NodeState._nodeState_ => parseNodeStateBody
      case Ping._ping_ => parsePingBody
      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }

  val wsPath = requestPrefix
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  protected var parser: Option[IncomingMessageParser] = None  // we can't have one before we have a node

  // writers are not thread safe - we use separate ones to avoid blocking
  val outgoingWriter = new JsonWriter // to serialize messages we get from our service actor and send out async
  val incomingWriter = new JsonWriter // to serialize sync responses from our Source (akka-http actors)

  var node: Option[Node] = None // our data
  var remoteNodes: Map[InetSocketAddress,String] = Map.empty

  /**
    * this is what we get from our RaceDataClient actor, i.e. what we receive from the RACE bus
    *
    * NOTE this is executed async - avoid data races
    */
  override def receiveData (data:Any): Unit = {
    data match {
      case newNode: Node =>
        node = Some(newNode)
        if (!parser.isDefined) {
          parser = Some(new IncomingMessageParser(newNode))
          info(s"node server '${newNode.id}' ready to accept connections")
        }
        // no need to send anything since we still get CDCs for changes and node sync is initiated by child nodes

      case cdc: ColumnDataChange =>
        pushChange(cdc, outgoingWriter)
    }
  }

  // TODO - this is not very efficient if we don't filter rows. We might move the filtering at least into the serializer
  def pushChange (cdc: ColumnDataChange, writer: JsonWriter): Unit = {
    ifSome(node) { node=>
      val targetId = cdc.columnId

      ifSome( node.columnList.get(targetId)) { col=>
        remoteNodes.foreach { e=>
          val (remoteAddr,remoteId) = e
          if (cdc.changeNodeId != remoteId) { // we don't send it back to where it came from
            if (col.updateFilter.sendToDownStream(remoteId)) {
              val filteredCdc = cdc.filter { r=>
                node.rowList.get(r) match {
                  case Some(row) => row.updateFilter.sendToDownStream(remoteId)
                  case None => false // unknown row
                }
              }
              if (filteredCdc.nonEmpty) {
                val m = TextMessage.Strict(writer.toJson(filteredCdc))
                pushTo(remoteAddr, m)
              }
            }
          }
        }
      }
    }
  }

  protected def parseIncoming (tm: TextMessage.Strict): Option[Any] = parser.flatMap( _.parse(tm.text))
  protected def isKnownColumn (id: String): Boolean = node.isDefined && node.get.columnList.columns.contains(id)
  protected def isLocal (id: String): Boolean = node.isDefined && node.get.isLocal(id)
  protected def isUpstream (id: String): Boolean = node.isDefined && node.get.isUpstream(id)

  override protected def handleConnectionLoss (remoteAddress: InetSocketAddress, cause: Try[Done]): Unit = {
    remoteNodes.get(remoteAddress) match {
      case Some(remoteId) =>
        publishData(NodeReachabilityChange(remoteId,false))
        remoteNodes = remoteNodes - remoteAddress
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
    * BEWARE - this is executed in a different (akka-http) thread. Use incomingWriter for sync responses
    */
  override protected def handleIncoming (remoteAddr: InetSocketAddress, m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parseIncoming(tm) match {
          case Some(ns: NodeState) => handleNodeState(remoteAddr, ns)
          case Some(cdc: ColumnDataChange) => handleColumnDataChange(remoteAddr, tm, cdc)
          case Some(ping: Ping) => handlePing(ping, remoteAddr)
          case _ => Nil // ignore all other
        }

      case _ => super.handleIncoming(remoteAddr, m)
    }
  }

  /**
    * a downstream node sent us its NodeState - send newer changes and own (filtered) NodeState back
    */
  def handleNodeState (remoteAddr: InetSocketAddress, ns: NodeState): Iterable[Message] = {
    val remoteNodeId = ns.nodeId
    remoteNodes = remoteNodes + (remoteAddr -> remoteNodeId)  // assoc should not change until connection is lost
    publishData(NodeReachabilityChange(remoteNodeId,true)) // let other actors know remote node is online

    def sendColumn (col: Column): Boolean = col.updateFilter.sendToDownStream(remoteNodeId)
    def receiveColumn (col: Column): Boolean = col.updateFilter.receiveFromDownStream(remoteNodeId,col.node)
    def sendRow (col: Column, row: Row[_]): Boolean = row.updateFilter.sendToDownStream(remoteNodeId)
    def receiveRow (col: Column, row: Row[_]): Boolean = row.updateFilter.receiveFromDownStream(remoteNodeId, col.node)

    withSomeOrElse(node, Seq.empty[Message]){ node=>
      info(s"processing remote NodeState: \n$ns")

      if (node.isKnownColumn(remoteNodeId)) {
        val nodeStateResponder = new NodeStateResponder(node,this, sendColumn, receiveColumn, sendRow, receiveRow)
        nodeStateResponder.getNodeStateReplies(ns).map( o=> TextMessage.Strict(incomingWriter.toJson(o)))

      } else {
        warning(s"rejecting NodeState from unknown node $remoteNodeId")
        Seq.empty[Message]
      }
    }
  }

  def handleColumnDataChange (remoteAddr: InetSocketAddress, m: Message, cdc: ColumnDataChange): Iterable[Message] = {
    for (node <- node) {
      remoteNodes.get(remoteAddr) match {
        case Some(senderNode) =>
          node.columnList.get(cdc.changeNodeId) match {
            case Some(col) =>
              if (col.updateFilter.receiveFromDownStream(cdc.changeNodeId, cdc.columnId)) {
                val filteredCdc = cdc.filter { r=>
                  node.rowList.get(r) match {
                    case Some(row) => row.updateFilter.receiveFromDownStream(cdc.changeNodeId, cdc.columnId)
                    case None => false // unknown row
                  }
                }
                if (filteredCdc.nonEmpty) publishData(filteredCdc)
              }
            case None => warning(s"change for unknown column ${cdc.columnId} ignored")
          }
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

  def isAcceptedChange(senderNodeId: String, cdc: ColumnDataChange): Boolean = {
    withSomeOrElse(node,false) { node=>
      val targetId = cdc.columnId

      if (cdc.changeNodeId != senderNodeId) {
        // TODO - what about nodes that report several columns?
        false
      } else {
        withSomeOrElse(node.columnList.get(targetId), false) { col =>
          //col.updateFilter.receiveFromDownStream(senderNodeId, targetId) && !isOutdatedChange(cdc)
          col.updateFilter.receiveFromDownStream(senderNodeId, targetId)
        }
      }
    }
  }

  def handlePing (ping: Ping, remoteAddr: InetSocketAddress): Iterable[Message] = {
    withSomeOrElse(node, Seq.empty[Message]){ node=>
      withSomeOrElse(remoteNodes.get(remoteAddr),Seq.empty[Message]){ remoteId=>
        if (remoteId == ping.sender && node.id == ping.receiver) {
          info(s"responding to ping from $remoteId")
          val pongTime = DateTime.now // TODO - do we need simTime here? This is not an actor
          val pongMsg = TextMessage.Strict(incomingWriter.toJson(Pong(pongTime,ping)))
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

