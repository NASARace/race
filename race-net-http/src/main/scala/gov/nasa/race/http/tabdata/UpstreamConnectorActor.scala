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
      case Some(cdc: ColumnDataChange) => publishFiltered(cdc)
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

  // we handle this here and don't publish to any channel
  def processUpstreamNodeState (ns: NodeState): Unit = {
    for (site <- site; upstreamId <- site.upstreamId) {
      if (ns.nodeId == upstreamId) {
        isRegistered = true
        startScheduler // now that we are registered with upstream we can schedule Pings

        var updateRequests = Seq.empty[(Path,DateTime)]

        ns.columnDataDates.foreach {e=>
          val (colId, colDate) = e

          columnData.get(colId) match {
            case Some(cd) =>
              val col = site.columnList(colId) // if we have a CD it also means we have a column for it
              if (colDate < cd.date) { // our data is newer - send change if we send this col to upstream
                if (col.updateFilter.sendToUpStream(ns.nodeId)) {
                  processOutgoingMessage(ColumnDataChange(colId, site.id, cd.date, cd.changesSince(colDate))) // send right away
                }
              } else if (colDate > cd.date) { // upstream is newer - send own NodeState
                if (col.updateFilter.receiveFromUpStream(ns.nodeId)) {
                  updateRequests = (colId -> cd.date) +: updateRequests
                }
              }
            case None =>
              warning(s"no data for column $colId")
          }
        }

        if (updateRequests.nonEmpty){
          processOutgoingMessage(new NodeState(site,updateRequests.reverse))
        }

      } else {
        warning(s"ignoring NodeState of non-upstream node ${ns.nodeId}")
      }
    }
  }

  def sendOwnNodeState: Boolean = {
    for (node <- site; upstreamId <- node.upstreamId) {
      val upstreamReceives = columnData.foldRight(Seq.empty[(Path, DateTime)]) { (e,acc) =>
        val (colId, cd) = e
        val col = node.columnList(colId)
        if (col.updateFilter.receiveFromUpStream(upstreamId)) {
          (colId -> cd.date) +: acc
        } else acc
      }

      if (upstreamReceives.nonEmpty) {
        processOutgoingMessage(new NodeState(node, upstreamReceives))
      }
    }
    true
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
      processOutgoingMessage(cdc)

    case RetryConnect =>
      if (connect) sendOwnNodeState
  }
}
