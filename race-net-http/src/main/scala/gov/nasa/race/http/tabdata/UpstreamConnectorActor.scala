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
import gov.nasa.race.http.{WSAdapterActor, WsMessageReader, WsMessageWriter}
import gov.nasa.race.uom.DateTime

/**
  * the upstream websocket adapter for tabdata
  * this connects to the upstream ServerRoute
  */
class UpstreamConnectorActor(override val config: Config) extends WSAdapterActor(config) {

  var parser: Option[IncomingMessageParser] = None  // can't be set before we have our Node info

  var site: Option[Node] = None
  var columnData: Map[Path,ColumnData] = Map.empty

  class IncomingMessageParser (val node: Node) extends BufferedStringJsonPullParser
                                                   with ColumnDataChangeParser with NodeStateParser {
    def parse(msg: String): Option[Any] = parseMessageSet(msg) {
      case ColumnDataChange._columnDataChange_ => parseColumnDataChangeBody
      case NodeState._nodeState_ => parseNodeStateBody
      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }

  class TabDataWriter extends WsMessageWriter {
    val writer = new JsonWriter

    override def write(o: Any): Option[Message] = {
      o match {
        case ss: NodeState => Some(TextMessage.Strict(writer.toJson(ss)))
        case cdc: ColumnDataChange => Some(TextMessage.Strict(writer.toJson(cdc)))
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

  // we override both incoming and outgoing processing functions to filter
  // based on message type and own state. This could also be done in the writer but that seems to obfuscated
  // since readers/writers should only be concerned about serialization/deserialization

  override def processIncomingMessage (msg: Message): Unit = {
    reader.read(msg) match {
      case Some(ns: NodeState) => processUpstreamNodeState(ns)
      case Some(cdc: ColumnDataChange) => publishFiltered(cdc)
      case Some(other) => warning(s"ignoring incoming upstream message $other")
      case None => // ignore
    }
  }

  override protected def processOutgoingMessage (o: Any): Unit = {
    for (
      node <- site;
      upstreamId <- node.upstreamId
    ) {
      o match {
        case ns: NodeState =>
          if (ns.nodeId == node.id) super.processOutgoingMessage(ns) // we only send our own
        case cdc: ColumnDataChange =>
          node.columnList.get(cdc.columnId) match {
            case Some(col) =>
              if (col.updateFilter.sendToUpStream(upstreamId)) super.processOutgoingMessage(cdc)
            case None => warning(s"change of unknown column not sent to upstream: ${cdc.columnId}")
          }
        case _ => // ignore - not an event we send out
      }
    }
  }

  // we handle this here and don't publish to any channel
  def processUpstreamNodeState (ns: NodeState): Unit = {
    for (
      site <- site;
      upstreamId <- site.upstreamId
    ) {
      if (ns.nodeId == upstreamId) {
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
    for (
      node <- site;
      upstreamId <- node.upstreamId
    ) {
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
