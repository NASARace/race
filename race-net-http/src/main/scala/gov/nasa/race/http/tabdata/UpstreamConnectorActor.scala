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

import java.nio.file.Path

import akka.actor.ActorRef
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
  * the upstream websocket adapter for tabdata
  * this connects to the upstream ServerRoute
  */
class UpstreamConnectorActor(override val config: Config) extends WSAdapterActor(config)
                                            with PeriodicRaceActor with ContinuousTimeRaceActor {

  var parser: Option[IncomingMessageParser] = None  // can't be set before we have our Node info

  var site: Option[Node] = None
  var columnData: Map[Path,ColumnData] = Map.empty

  var isRegistered: Boolean = false  // did we already receive our upstream NodeState

  // liveness and QoS data
  var nPings: Int = 0
  var lastPingTime: DateTime = DateTime.UndefinedDateTime
  var lastPongTime: DateTime = DateTime.UndefinedDateTime


  override def defaultTickInterval: FiniteDuration = 30.seconds // normal timeout for websockets is 60 sec

  class IncomingMessageParser (val node: Node) extends BufferedStringJsonPullParser
                                                   with ColumnDataChangeParser with NodeStateParser with PongParser {
    def parse(msg: String): Option[Any] = parseMessageSet(msg) {
      case ColumnDataChange._columnDataChange_ => parseColumnDataChangeBody
      case NodeState._nodeState_ => parseNodeStateBody
      case Ping._pong_ => parsePongBody
      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }

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
        case tm: TextMessage.Strict => parser.flatMap(_.parse(tm.text))
        case _ => None // ignore
      }
    }
  }

  override def createWriter: WsMessageWriter = new TabDataWriter
  override def createReader: WsMessageReader = new TabDataReader

  // there is no point scheduling before we are connected /and/ registered (NodeStates have been exchanged)
  override def startScheduler: Unit = {
    if (isRegistered) super.startScheduler
  }

  override def onRaceTick: Unit = {
    if (isRegistered){
      pingUpstream
    }
  }

  override def disconnect(): Unit = {
    info("disconnecting")
    super.disconnect()

    for (node <- site; upstreamId <- node.upstreamId){ publish(NodeReachabilityChange(upstreamId,false)) }

    lastPingTime = DateTime.UndefinedDateTime
    lastPongTime = DateTime.UndefinedDateTime
    nPings = 0
    isRegistered = false
  }

  def pingUpstream: Unit = {
    for ( node <- site; upstreamId <- node.upstreamId) {
      if (lastPongTime < lastPingTime) { // means we have not gotten a reply during the last ping cycle
        warning(s"no response for last ping at $lastPingTime, trying to reconnect")
        disconnect()
        reconnect()

      } else {
        nPings += 1
        lastPingTime = DateTime.now // TODO - do we need simTime here?
        val ping = Ping(node.id, upstreamId,nPings,lastPingTime)
        processOutgoingMessage(ping)
      }
    }
  }

  override protected def handleSendFailure(msg: String): Unit = {
    warning(msg)
    disconnect()
    reconnect()
  }

  // we override both incoming and outgoing processing functions to filter
  // based on message type and own state. This could also be done in the writer but that seems to obfuscated
  // since readers/writers should only be concerned about serialization/deserialization

  override def processIncomingMessage (msg: Message): Unit = {
    reader.read(msg) match {
      case Some(ns: NodeState) => processUpstreamNodeState(ns)
      case Some(cdc: ColumnDataChange) => processUpstreamColumnDataChange(cdc)
      case Some(pong: Pong) => processUpstreamPong(pong)
      case Some(other) => warning(s"ignoring incoming upstream message $other")
      case None => // ignore
    }
  }

  override protected def processOutgoingMessage (o: Any): Unit = {
    for (node <- site; upstreamId <- node.upstreamId) {
      o match {
        case ns: NodeState =>
          if (ns.nodeId == node.id) super.processOutgoingMessage(ns) // we only send our own

        case cdc: ColumnDataChange =>
          node.columnList.get(cdc.columnId) match {
            case Some(col) =>
              if (col.updateFilter.sendToUpStream(upstreamId)) super.processOutgoingMessage(cdc)
            case None => warning(s"change of unknown column not sent to upstream: ${cdc.columnId}")
          }

        case ping: Ping =>
          super.processOutgoingMessage(ping)

        case _ => // ignore - not an event we send out
      }
    }
  }

  def processUpstreamColumnDataChange (rawCdc: ColumnDataChange): Unit = {
    for (node <- site; upstreamId <- node.upstreamId) {
      node.columnList.get(rawCdc.columnId) match {
        case Some(col) =>
          if (col.updateFilter.receiveFromUpStream(upstreamId)) {
            val cdc = rawCdc.filter { r =>
              node.rowList.get(r) match {
                case Some(row) => row.updateFilter.receiveFromUpStream(upstreamId)
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
    * this is the upstream response to our own NodeState
    */
  def processUpstreamNodeState (ns: NodeState): Unit = {
    for (node <- site; upstreamId <- node.upstreamId) {
      if (ns.nodeId == upstreamId) {
        isRegistered = true
        startScheduler // now that we are registered with upstream we can schedule Pings
        publish(NodeReachabilityChange(upstreamId,true))

        def sendColumn (col: Column): Boolean = col.updateFilter.sendToUpStream(upstreamId)
        def receiveColumn (col: Column): Boolean = col.updateFilter.receiveFromUpStream(upstreamId)
        def sendRow (col: Column, row: AnyRow) = row.updateFilter.sendToUpStream(upstreamId)
        def receiveRow (col: Column, row: AnyRow): Boolean = row.updateFilter.receiveFromUpStream(upstreamId)

        val nodeStateResponder = new NodeStateResponder(node,columnData,this,
                                                        sendColumn, receiveColumn, sendRow, receiveRow)
        nodeStateResponder.getNodeStateReplies(ns).foreach( processOutgoingMessage)

      } else {
        warning(s"ignoring NodeState of non-upstream node ${ns.nodeId}")
      }
    }
  }

  def processUpstreamPong (pong: Pong): Unit = {
    for (node <- site; upstreamId <- node.upstreamId) {
      if (isConnected) {
        val ping = pong.ping
        if (ping.request != nPings) warning(s"out of order pong: $pong")
        if (ping.sender != node.id) warning(s"not our request: $pong")
        if (ping.receiver != upstreamId) warning( s"not our upstream: $pong")
        lastPongTime = pong.date

        val dt = pong.date - ping.date
        if (dt.toMillis < 0) warning(s"negative round trip time: $pong")
        info(s"ping response time from upstream: $dt")
      }
    }
  }

  def sendOwnNodeState: Unit = {
    for (node <- site; upstreamId <- node.upstreamId) {
      val extCDs = mutable.Buffer.empty[ColumnDate]
      val locCDs = mutable.Buffer.empty[(Path,Seq[RowDate])]

      node.columnList.orderedEntries(columnData).foreach { e=>
        val (colId,cd) = e
        val col = node.columnList(colId)
        if (col.updateFilter.receiveFromUpStream(upstreamId)) {
          if (col.node == node.id) locCDs += (colId -> cd.orderedCellDates(node.rowList))
          else extCDs += (colId -> cd.date)
        }
      }

      if (extCDs.nonEmpty || locCDs.nonEmpty) {
        processOutgoingMessage( new NodeState(node,extCDs.toSeq,locCDs.toSeq))
      }
    }
  }

  def sendColumnDataChange (rawCdc: ColumnDataChange): Unit = {
    for (node <- site; upstreamId <- node.upstreamId) {
      val cdc = rawCdc.filter { r =>
        node.rowList.get(r) match {
          case Some(row) => row.updateFilter.sendToUpStream(upstreamId)
          case None => false
        }
      }

      if (cdc.nonEmpty) processOutgoingMessage(cdc)
    }
  }

  //--- standard RaceActor callbacks

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)) {
      if (isConnected) sendOwnNodeState
      true
    } else false
  }

  override def handleMessage: Receive = {
    case BusEvent(_,s: Node,_) =>
      site = Some(s)
      parser = Some(new IncomingMessageParser(s))

    case BusEvent(_, cd: ColumnData, _) => // new local or remote providerData -> just update our own map
      columnData = columnData + (cd.id -> cd)

    case BusEvent(_,cdc: ColumnDataChange,_) =>
      sendColumnDataChange(cdc)

    case RetryConnect =>
      if (connect) sendOwnNodeState
  }
}
