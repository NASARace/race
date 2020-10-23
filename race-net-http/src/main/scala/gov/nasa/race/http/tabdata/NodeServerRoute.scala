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
import scala.collection.mutable.ArrayBuffer

/**
  * the route that handles ColumnData synchronization from/to child node clients through a websocket
  *
  * note that we don't register clients (child nodes) or send our NodeState to them before they send us
  * their NodeState (at start, after connecting)
  */
class NodeServerRoute(val parent: ParentActor, val config: Config) extends PushWSRaceRoute with RaceDataClient {

  val wsPath = requestPrefix
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  protected var parser: Option[IncomingMessageParser] = None

  // writers are not thread safe - we use separate ones to avoid blocking
  val outgoingWriter = new JsonWriter // to serialize messages we get from our service actor and send out async
  val incomingWriter = new JsonWriter // to serialize sync responses from our Source (akka-http actors)

  var site: Option[Node] = None
  var columnData: Map[Path,ColumnData] = Map.empty

  var remoteNodes: Map[InetSocketAddress,Path] = Map.empty

  /**
    * this is what we get from our service actor and push to connected nodes
    *
    * NOTE this is executed async - avoid data races
    */
  override def receiveData(data:Any): Unit = {
    data match {
      case s: Node =>  // internal initialization
        site = Some(s)
        parser = Some(new IncomingMessageParser(s))
        info(s"node server '${s.id}' ready to accept connections")

      case rl: RowList => // those are local or upstream catalog changes
        site = site.map( _.copy(rowList=rl))
        parser = Some(new IncomingMessageParser(site.get))
        push(TextMessage.Strict(outgoingWriter.toJson(rl)))
        info(s"pushing new field catalog ${rl.id}")

      case cl: ColumnList =>
        site = site.map( _.copy(columnList=cl))
        parser = Some(new IncomingMessageParser(site.get))
        push(TextMessage.Strict(outgoingWriter.toJson(cl)))
        info(s"pushing new provider catalog ${cl.id}")

      case cd: ColumnData => // new local or remote providerData -> just update our own map
        columnData = columnData + (cd.id -> cd)

      case cdc: ColumnDataChange =>
        pushChange(cdc, outgoingWriter)
    }
  }

  def pushChange (cdc: ColumnDataChange, writer: JsonWriter): Unit = {
    ifSome(site) { site=>
      val targetId = cdc.columnId

      ifSome( site.columnList.get(targetId)) { col=>
        val m = TextMessage.Strict(writer.toJson(cdc))

        // this only sends to connected nodes to which we export this column
        remoteNodes.foreach { e=>
          val (remoteAddr,remoteId) = e
          if (cdc.changeNodeId != remoteId) { // we don't send it back to where it came from
            if (col.updateFilter.sendToDownStream(remoteId)) {
              pushTo(remoteAddr, m)
            }
          }
        }
      }
    }
  }

  protected def parseIncoming (tm: TextMessage.Strict): Option[Any] = parser.flatMap( _.parse(tm.text))
  protected def isKnownColumn (id: Path): Boolean = site.isDefined && site.get.columnList.columns.contains(id)
  protected def isLocal (id: Path): Boolean = site.isDefined && site.get.isLocal(id)
  protected def isUpstream (id: Path): Boolean = site.isDefined && site.get.isUpstream(id)

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
    remoteNodes = remoteNodes + (remoteAddr -> remoteNodeId)

    withSomeOrElse(site, Seq.empty[Message]){ site=>
      info(s"processing remote NodeState: $ns")

      if (site.isKnownColumn(remoteNodeId)) {
        var updateRequests = Seq.empty[(Path,DateTime)]
        var response = Seq.empty[Message]

        ns.columnDataDates.foreach { e=>
          val (colId, colDate) = e

          columnData.get(colId) match {
            case Some(cd) =>
              val col = site.columnList(colId)  // if we have a CD it also means we have a column for it

              if (colDate < cd.date) { // our data is newer - send change if we send this col to this downstream node
                if (col.updateFilter.sendToDownStream(remoteNodeId)) {
                  // we send this even if there are no changes, which indicates to remote that their cd.date is outdated
                  val cdc = ColumnDataChange(colId, site.id, cd.date, cd.changesSince(colDate))
                  response = TextMessage.Strict(incomingWriter.toJson(cdc)) +: response
                }

              } else if (colDate > cd.date) { // remote data is newer - store for request if we receive this col from this downstream node
                if (col.updateFilter.receiveFromDownStream(remoteNodeId,col.node)) {
                  updateRequests = (colId -> cd.date) +: updateRequests
                }
              }

            case None => // we don't know this remote column
              warning(s"no data for column ${e._1}")
          }
        }

        // TODO - what about CDs we export to remote that are not in the NS? (indicates outdated CLs)

        if (updateRequests.nonEmpty) {
          // send own NS first so that we get the remote data before processing our changes
          TextMessage.Strict(incomingWriter.toJson(new NodeState(site,updateRequests))) +: response
        } else {
          response
        }.reverse

      } else {
        warning(s"rejecting NodeState from unknown node $remoteNodeId")
        Seq.empty[Message]
      }
    }
  }

  def handleColumnDataChange (remoteAddr: InetSocketAddress, m: Message, cdc: ColumnDataChange): Iterable[Message] = {
    remoteNodes.get(remoteAddr) match {
      case Some(senderNode) =>
        if (isAcceptedChange(senderNode,cdc)) {
          //pushToFiltered (m) (_ != remoteAddr) // send original change to all other providers
          publishData (cdc) // update our own service actor (which might result in receiveData callbacks if we add field evals)

        } else {
          warning (s"rejected change: $cdc")
        }
      case None =>
        warning(s"ignoring change from unknown sender $remoteAddr: $cdc")
    }
    Nil // no sync reply
  }

  def isOutdatedChange (cdc: ColumnDataChange): Boolean = {
    columnData.get(cdc.columnId) match {
      case Some(cd) => cd.date >= cdc.date
      case None => false // we don't have ProviderData for this yet
    }
  }

  def isAcceptedChange(senderNodeId: Path, cdc: ColumnDataChange): Boolean = {
    withSomeOrElse(site,false) { site=>
      val targetId = cdc.columnId

      if (cdc.changeNodeId != senderNodeId) {
        // TODO - what about nodes that report several columns?
        false
      } else {
        withSomeOrElse(site.columnList.get(targetId), false) { col =>
          col.updateFilter.receiveFromDownStream(senderNodeId, targetId) && !isOutdatedChange(cdc)
        }
      }
    }
  }

  def handlePing (ping: Ping, remoteAddr: InetSocketAddress): Iterable[Message] = {
    withSomeOrElse(site,Seq.empty[Message]){ node=>
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
        if (site.isDefined) {
          promoteToWebSocket
        } else {
          complete(StatusCodes.PreconditionFailed, "server not yet initialized")
        }
      }
    }
  }


  /**
    * the parser for incoming messages. Note this is from trusted/checked connections
    */
  class IncomingMessageParser (val node: Node) extends BufferedStringJsonPullParser
                                                            with ColumnDataChangeParser with NodeStateParser with PingParser {
    def parse(msg: String): Option[Any] = parseMessageSet(msg) {
      case ColumnDataChange._columnDataChange_ => parseColumnDataChangeBody
      case NodeState._nodeState_ => parseNodeStateBody
      case Ping._ping_ => parsePingBody
      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }
}

