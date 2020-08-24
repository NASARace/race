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
import gov.nasa.race.http.{WSAdapterActor, WsMessageReader, WsMessageWriter}

/**
  * the upstream websocket adapter for tabdata
  * this connects to the upstream ServerRoute
  */
class TabDataAdapterActor (override val config: Config) extends WSAdapterActor(config) {

  var site: Option[Node] = None
  var parser: Option[IncomingMessageParser] = None

  class IncomingMessageParser (val node: Node) extends BufferedStringJsonPullParser
                                                   with ProviderDataChangeParser with NodeStateParser {
    def parse(msg: String): Option[Any] = parseMessageSet(msg) {
      case ProviderDataChange._providerDataChange_ => parseProviderDataChangeBody
      case NodeState._nodeState_ => parseSiteStateBody

      case other => warning(s"ignoring unknown message type '$other'"); None
    }
  }

  class TabDataWriter extends WsMessageWriter {
    val writer = new JsonWriter

    override def write(o: Any): Option[Message] = {
      o match {
        case ss: NodeState => Some(TextMessage.Strict(writer.toJson(ss)))
        case pdc: ProviderDataChange => Some(TextMessage.Strict(writer.toJson(pdc)))

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

  override def handleMessage: Receive = {
    case BusEvent(_,s: Node,_) =>
      site = Some(s)
      parser = Some(new IncomingMessageParser(s))

    case BusEvent(_,msg,_) => processOutgoingMessage(msg)
    case RetryConnect => connect
  }
}
