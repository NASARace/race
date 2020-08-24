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

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{get, path}
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonParseException, JsonWriter}
import gov.nasa.race.core.{ParentActor, RaceDataClient}
import gov.nasa.race.http.{PushWSRaceRoute, SiteRoute}
import gov.nasa.race.ifSome
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.Iterable
import scala.collection.mutable.ArrayBuffer

/**
  * the route that handles provider synchronization through a websocket
  */
class NodeServerRoute(val parent: ParentActor, val config: Config) extends PushWSRaceRoute with RaceDataClient {

  val wsPath = requestPrefix
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  protected var parser: Option[IncomingMessageParser] = None
  val writer = new JsonWriter

  var site: Option[Node] = None
  var providerData: Map[String,ProviderData] = Map.empty

  /**
    * this is what we get from our service actor (which gets it from the TabDataServiceActor)
    * NOTE this is executed async - avoid data races
    */
  override def receiveData(data:Any): Unit = {
    data match {
      case s: Node =>  // internal initialization
        site = Some(s)
        parser = Some(new IncomingMessageParser(s))
        info(s"node server '${s.id}' ready to accept connections")

      case fc: FieldCatalog => // those are local or upstream catalog changes
        site = site.map( _.copy(fieldCatalog=fc))
        parser = Some(new IncomingMessageParser(site.get))
        push(TextMessage.Strict(writer.toJson(fc)))
        info(s"pushing new field catalog ${fc.id}")

      case pc: ProviderCatalog =>
        site = site.map( _.copy(providerCatalog=pc))
        parser = Some(new IncomingMessageParser(site.get))
        push(TextMessage.Strict(writer.toJson(pc)))
        info(s"pushing new provider catalog ${pc.id}")

      case pd: ProviderData => // new local or remote providerData -> just update our own map
        providerData = providerData + (pd.providerId -> pd)

      case pdc: ProviderDataChange => // local or upstream changes
        ifSome(site) { site =>
          if (site.isLocal(pdc.changeNodeId)) {
            push(TextMessage.Strict(writer.toJson(pdc)))
            info(s"pushing new local change of ${pdc.providerId}")

          } else if (site.isUpstream(pdc.changeNodeId)) {
            val sendPdc = pdc.copy(changeNodeId = site.id)
            push(TextMessage.Strict(writer.toJson(sendPdc)))
            info(s"pushing new external change of ${pdc.providerId} from node '${pdc.changeNodeId}'")

          } else {
            warning(s"ignoring ProviderDataChange from invalid source: $pdc")
          }
        }
    }
  }

  protected def parseIncoming (tm: TextMessage.Strict): Option[Any] = parser.flatMap( _.parse(tm.text))
  protected def isKnownProvider (id: String): Boolean = site.isDefined && site.get.providerCatalog.providers.contains(id)
  protected def isLocal (id: String): Boolean = site.isDefined && site.get.isLocal(id)
  protected def isUpstream (id: String): Boolean = site.isDefined && site.get.isUpstream(id)

  /**
    * this is what we receive through the websocket (from connected providers)
    */
  override protected def handleIncoming (remoteAddr: InetSocketAddress, m: Message): Iterable[Message] = {
    var response: List[Message] = Nil

    m match {
      case tm: TextMessage.Strict =>
        parseIncoming(tm) match {

          case Some(ss: NodeState) => // this is in response to our own SiteState (which was sent during connection)
            ifSome(site){ site=>
              if (site.isKnownProvider(ss.siteId)) {
                //--- send changed catalogs
                if (!site.isFieldCatalogUpToDate(ss)) response = TextMessage.Strict(writer.toJson(site.fieldCatalog)) :: response
                if (!site.isProviderCatalogUpToDate(ss)) response = TextMessage.Strict(writer.toJson(site.providerCatalog)) :: response

                //--- send PD changes since SS
                ss.providerDataDates.foreach {e=>
                  providerData.get(e._1) match {
                    case Some(pd) =>
                      pd.changesSince(e._2,site.id) match {
                        case Some(pdc) => response = TextMessage.Strict(writer.toJson(pdc)) :: response
                        case None => // pd is up-to-date
                      }
                    case None => // we don't have that PD
                  }
                }
              }
            }

          case Some(pdc: ProviderDataChange) =>
            if (isValidChange(pdc)) {
              pushToFiltered(tm)(_ != remoteAddr) // send original change to all other providers
              publishData(pdc) // update our own service actor (which might result in receiveData callbacks if we add field evals)
            } else warning(s"ignoring invalid change: $pdc")

          case _ => // ignore all other
        }
        response.reverse

      case _ =>
        super.handleIncoming(remoteAddr, m)
    }
  }

  def isOutdatedChange (pdc: ProviderDataChange): Boolean = {
    providerData.get(pdc.providerId) match {
      case Some(pd) => pd.date >= pdc.date
      case None => false // we don't have ProviderData for this yet
    }
  }

  def isValidChange (pdc: ProviderDataChange): Boolean = {
    isKnownProvider(pdc.providerId) && (pdc.changeNodeId == pdc.providerId) && !isOutdatedChange(pdc)
  }

  override protected def initializeConnection (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
    ifSome(site) { site=>
      // push own state for each new connection so that Providers can send their updates and their state
      pushTo( remoteAddr, queue, TextMessage.Strict(writer.toJson(new NodeState(site,providerData))))
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
                                                            with ProviderDataChangeParser with NodeStateParser {
    def parse(msg: String): Option[Any] = parseMessageSet(msg) {
      case ProviderDataChange._providerDataChange_ => parseProviderDataChangeBody
      case NodeState._nodeState_ => parseSiteStateBody
      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }
}

