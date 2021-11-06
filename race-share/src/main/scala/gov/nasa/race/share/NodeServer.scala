/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.share

import com.typesafe.config.Config
import gov.nasa.race.core.BusEvent
import gov.nasa.race.http.HttpServer
import gov.nasa.race.util.{NumUtils, StringUtils}

object NodeServer {
  val DefaultNodeServerIfc = "::0"  // any interface
  val DefaultNodeServerPort = 7000
}

/**
  * an HttpServer that gets its interface and port from Node.nodeList.self
  */
class NodeServer (override val config: Config) extends HttpServer(config) {
  import NodeServer._

  var node: Option[Node] = None // we get this from the UpdateActor upon initialization

  override def getInterface: String = node match {
    case Some(n) => StringUtils.getNonEmptyOrElse(n.nodeList.self.host, DefaultNodeServerIfc)
    case None => DefaultNodeServerIfc  // TODO - should we throw an exception here?
  }

  override def getPort: Int = node match {
    case Some(n) => NumUtils.getPositiveIntOrElse(n.nodeList.self.port, DefaultNodeServerPort)
    case None => DefaultNodeServerPort
  }

  override def handleMessage: Receive = {
    case BusEvent(_, n: Node, _) => node = Some(n) // we do need the node to get our hostname/port
    case e:BusEvent => // other bus events we ignore
  }
}
