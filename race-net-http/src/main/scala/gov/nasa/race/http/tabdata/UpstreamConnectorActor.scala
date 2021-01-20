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
import com.typesafe.config.Config
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonWriter}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor}
import gov.nasa.race.http.{WSAdapterActor, WsMessageReader, WsMessageWriter}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * the upstream websocket adapter that connects to the upstream server running a NodeServerRoute
  *
  * this actor implements the client side of the synchronization protocol, which has to guarantee
  *〖
  *   - minimal network configuration (only respective upstream address needs to be known a priori)
  *   - client node therefore initiates sync with server
  *   - client actively monitors server node connection to keep websocket alive and ensure proper server response
  * 〗
  */
class UpstreamConnectorActor(override val config: Config) extends WSAdapterActor(config)
                                            with PeriodicRaceActor with ContinuousTimeRaceActor {
  class TabDataWriter extends WsMessageWriter {
    val jw = new JsonWriter

    override def write(o: Any): Option[Message] = {
      o match {
        case ss: NodeState => Some(TextMessage.Strict(jw.toJson(ss)))
        case cdc: ColumnDataChange => Some(TextMessage.Strict(jw.toJson(cdc)))
        case ping: Ping => Some(TextMessage.Strict(jw.toJson(ping)))
        case _ => None // we don't send other messages upstream
      }
    }
  }

  class TabDataReader extends WsMessageReader {
    override def read(m: Message): Option[Any] = {
      m match {
        case tm: TextMessage.Strict => state.parseIncomingMessage(tm.text)
        case _ => None // ignore
      }
    }
  }

  /**
    * state specific RaceActor/WSAdapterActor behavior
    */
  trait InternalConnectorState {
    //--- RaceActor
    def handleMessage: Receive
    def onRaceTick(): Unit
    def onConnect(): Unit
    def onDisconnect(): Unit

    //--- WSAdapterActor
    def processIncomingMessage (msg: Message): Unit // what we do with parsed incoming messages

    //--- UpstreamConnectorActor
    def parseIncomingMessage (msg: String): Option[Any]  // what incoming messages we parse
  }

  /**
    * a state in which we are connected and have a node. We monitor the connection and parse all incoming messages
    */
  trait ConnectedState extends InternalConnectorState {
    def upstreamId: String
    def node: Node

    // since this is run per message we need to cache and buffer to avoid allocation
    class IncomingMessageParser extends BufferedStringJsonPullParser
                                with ColumnDataChangeParser with NodeStateParser with PongParser {
      override def rowList = node.rowList

      def parse (msg: String): Option[Any] = parseMessageSet(msg) {
        case ColumnDataChange._columnDataChange_ => parseColumnDataChangeBody
        case NodeState._nodeState_ => parseNodeStateBody
        case Ping._pong_ => parsePongBody
        case other => warning(s"ignoring unknown message type '$other'"); None
      }
    }

    val parser = new IncomingMessageParser

    //--- liveness and QoS data
    var nPings: Int = 0
    var lastPingTime: DateTime = DateTime.UndefinedDateTime
    var lastPongTime: DateTime = DateTime.UndefinedDateTime

    override def onConnect(): Unit = warning(s"ignoring onConnect since already connected")

    override def onDisconnect(): Unit = {
      publish( NodeReachabilityChange(upstreamId,false) )

      lastPingTime = DateTime.UndefinedDateTime
      lastPongTime = DateTime.UndefinedDateTime
      nPings = 0

      state = if (isTerminating) new TerminatedState else new ReconnectingState(upstreamId, node)
    }

    override def onRaceTick(): Unit = pingUpstream

    override def parseIncomingMessage (msg: String): Option[Any] = parser.parse(msg)

    /**
      * we do active pings for the dual purpose of (1) keep the web socket alive w/o overly aggressive global config,
      * and (2) to ensure end-to-end connection with the corresponding upstream NodeServerRoute HttpServer actor
      */
    def pingUpstream: Unit = {
      if (lastPongTime < lastPingTime) { // means we have not gotten a reply during the last ping cycle
        warning(s"no response for last ping at $lastPingTime, trying to reconnect")
        disconnect() // this will result in a onDisconnect call, which causes a state transition

      } else {
        nPings += 1
        lastPingTime = DateTime.now // TODO - do we need simTime here?
        val ping = Ping(node.id, upstreamId,nPings,lastPingTime)
        processOutgoingMessage(ping)
      }
    }

    /**
      * connection check response
      */
    def processUpstreamPong (pong: Pong): Unit = {
      val ping = pong.ping
      if (ping.request != nPings) warning(s"out of order pong: $pong")
      if (ping.sender != node.id) warning(s"not our request: $pong")
      if (ping.receiver != upstreamId) warning( s"not our upstream: $pong")
      lastPongTime = pong.date

      val dt = pong.date.timeSince(ping.date)
      if (dt.toMillis < 0) warning(s"negative round trip time: $pong")
      info(s"ping response time from upstream: $dt")
    }

    /**
      * this is a CDC we receive from the upstream socket
      */
    def processUpstreamColumnDataChange (rawCdc: ColumnDataChange): Unit = {
      node.columnList.get(rawCdc.columnId) match {
        case Some(col) =>
          if (col.updateFilter.receiveFromUpStream(node.upstreamId.get)) {
            val cdc = rawCdc.filter { r =>
              node.rowList.get(r) match {
                case Some(row) => row.updateFilter.receiveFromUpStream(node.upstreamId.get)
                case None => false
              }
            }
            if (cdc.nonEmpty) publishFiltered(cdc)
          }
        case None => // ignore
      }
    }
  }

  /**
    * in this state we don't parse or process any messages to/from upstream but just wait for the
    * internal node message to arrive, which triggers a state change
    *
    * this state should only be active between RaceInitialize and RaceStart
    */
  class InitialState extends InternalConnectorState {
    override def handleMessage: Receive = {
      case BusEvent(_,newNode: Node,_) => setNode(newNode)
      case _ => // ignore
    }

    def setNode (newNode: Node): Unit = {
      if (newNode.upstreamId.isDefined) {
        val upstreamId = newNode.upstreamId.get
        if (isConnected) {
          state = new SynchronizingState(upstreamId, newNode)
        } else {
          state = new ReconnectingState(upstreamId, newNode)
        }
      }
    }

    //--- we don't process anything else
    override def onRaceTick(): Unit = {}
    override def onConnect(): Unit = {}
    override def onDisconnect(): Unit = {}
    override def processIncomingMessage (msg: Message): Unit = {}
    override def parseIncomingMessage (msg: String): Option[Any] = None
  }

  /**
    * this is what we enter once we are connected and have a node. This sends the own NodeState and processes the upstream replies
    * We do not send out CDCs from the bus yet, which we shouldn't receive from the bus until we published that
    * the upstream node is connected and synced
    */
  class SynchronizingState (val upstreamId: String, var node: Node) extends ConnectedState {

    info(s"starting upstream synchronization with: $upstreamId")

    override def handleMessage: Receive = {
      case BusEvent(_,newNode: Node,_) => node = newNode
      case BusEvent(_,cdc: ColumnDataChange,_) => // we don't need to process this since Node messages contain those changes
      case _ => // ignore
    }

    override def onConnect(): Unit = sendOwnNodeState

    def sendOwnNodeState: Unit = {
      val extCDs = mutable.Buffer.empty[ColumnDatePair]
      val locCDs = mutable.Buffer.empty[(String,Seq[RowDatePair])]

      node.columnList.orderedEntries(node.columnDatas).foreach { e=>
        val (colId,cd) = e
        val col = node.columnList(colId)
        if (col.updateFilter.receiveFromUpStream(node.upstreamId.get)) {
          if (col.node == node.id) locCDs += (colId -> cd.orderedCellDates(node.rowList))
          else extCDs += (colId -> cd.date)
        }
      }

      if (extCDs.nonEmpty || locCDs.nonEmpty) {
        processOutgoingMessage( NodeState(node,extCDs.toSeq,locCDs.toSeq))
      }
    }

    override def processIncomingMessage(msg: Message): Unit = {
      reader.read(msg) match {
        case Some(ns: NodeState) => processUpstreamNodeState(ns)
        case Some(cdc: ColumnDataChange) => processUpstreamColumnDataChange(cdc)
        case Some(pong: Pong) => processUpstreamPong(pong)
        case Some(other) => warning(s"ignoring incoming upstream message $other")
        case None => // ignore
      }
    }

    /**
      * this is the upstream response to our own NodeState
      */
    def processUpstreamNodeState (ns: NodeState): Unit = {
      if (ns.nodeId == upstreamId) {
        def sendColumn (col: Column): Boolean = col.updateFilter.sendToUpStream(upstreamId)
        def receiveColumn (col: Column): Boolean = col.updateFilter.receiveFromUpStream(upstreamId)
        def sendRow (col: Column, row: Row[_]): Boolean = row.updateFilter.sendToUpStream(upstreamId)
        def receiveRow (col: Column, row: Row[_]): Boolean = row.updateFilter.receiveFromUpStream(upstreamId)

        if (ns.readOnlyColumns.nonEmpty || ns.readWriteColumns.nonEmpty) {
          val nodeStateResponder = new NodeStateResponder(node, UpstreamConnectorActor.this,
            sendColumn, receiveColumn, sendRow, receiveRow)
          nodeStateResponder.getNodeStateReplies(ns).foreach(processOutgoingMessage)
        }

        startScheduler // now that we are registered with upstream we can schedule Pings
        publish( NodeReachabilityChange(upstreamId,true)) // this indicates the sync with upstream is complete

      } else {
        warning(s"ignoring NodeState of non-upstream node ${ns.nodeId}")
      }
    }
  }

  /**
    * connected state after NodeState sync. This is the mode in which we send and receive CDCs
    */
  class SynchronizedState (val upstreamId: String, var node: Node) extends ConnectedState {

    info(s"synchronized with upstream: $upstreamId")

    override def handleMessage: Receive = {
      case BusEvent(_,newNode: Node,_) => node = newNode
      case BusEvent(_,cdc: ColumnDataChange,_) => sendColumnDataChange(cdc)
      case _ => // ignore
    }

    def sendColumnDataChange (rawCdc: ColumnDataChange): Unit = {
      if (rawCdc.changeNodeId != node.id) return  // ? what about child node changes

      val cdc = rawCdc.filter { r =>
        node.rowList.get(r) match {
          case Some(row) => row.updateFilter.sendToUpStream(node.upstreamId.get)
          case None => false
        }
      }

      if (cdc.nonEmpty) processOutgoingMessage(cdc)
    }

    override def processIncomingMessage (msg: Message): Unit = {
      reader.read(msg) match {
        case Some(cdc: ColumnDataChange) => processUpstreamColumnDataChange(cdc)
        case Some(pong: Pong) => processUpstreamPong(pong)
        case Some(other) => warning(s"ignoring incoming upstream message $other")
        case None => // ignore
      }
    }
  }

  /**
    * disconnected state in which we only keep track of node changes and periodically try to reconnect, which would
    * trigger a new upstream synchronization
    */
  class ReconnectingState (val upstreamId: String, var node: Node) extends InternalConnectorState {

    info(s"trying to reconnect with upstream: $upstreamId")

    override def handleMessage: Receive = {
      case BusEvent(_,newNode: Node,_) => node = newNode
      case RetryConnect => reconnect()
      case _ => // ignore
    }

    override def onRaceTick(): Unit = connect() // if successful it will result in a onConnect call

    override def onConnect(): Unit = {
      state = new SynchronizingState(upstreamId,node)
    }

    override def onDisconnect(): Unit = {}

    //--- no incoming messages
    override def parseIncomingMessage(msg: String): Option[Any] = None
    override def processIncomingMessage(msg: Message): Unit = {}
  }

  /**
    * end state in which we don't try to reconnect and ignore incoming and outgoing messages
    */
  class TerminatedState extends InternalConnectorState {

    info(s"disconnected from upstream")

    override def handleMessage: Receive = {
      case _ => // ignore
    }

    override def onRaceTick(): Unit = {}
    override def onConnect(): Unit = {}
    override def onDisconnect(): Unit = {}
    override def processIncomingMessage(msg: Message): Unit = {}
    override def parseIncomingMessage(msg: String): Option[Any] = None
  }

  //--- begin actor implementation

  var state: InternalConnectorState = new InitialState

  override def defaultTickInterval: FiniteDuration = 30.seconds // normal timeout for websockets is 60 sec

  //--- those are hardwired
  override def createWriter: WsMessageWriter = new TabDataWriter
  override def createReader: WsMessageReader = new TabDataReader

  //--- standard RaceActor callbacks

  override def handleMessage: Receive = state.handleMessage

  override def onRaceTick(): Unit = state.onRaceTick()

  //--- WSAdapterActor overrides

  override protected def handleSendFailure(msg: String): Unit = {
    warning(msg)
    disconnect()
    reconnect()
  }

  override def onConnect(): Unit = state.onConnect()
  override def onDisconnect(): Unit = state.onDisconnect()
  override protected def processIncomingMessage (msg: Message): Unit = state.processIncomingMessage(msg)

}
