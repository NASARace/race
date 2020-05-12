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

package gov.nasa.race.http

import java.net.InetSocketAddress

import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.headers.CustomHeader

object RealRemoteAddress {
  val name = "gov.nasa.race.http.RealRemoteAddress"

  def apply (conn: IncomingConnection) = new RealRemoteAddress(conn.remoteAddress)
}

/**
  * a CustomHeader we use to keep track of remote addresses of the current route
  *
  * Note that while there is a standard Remote-Address header it is optional and is explicitly set by the remote
  * machine, hence it is not safe to rely on existence/value. In fact, we might want to check if there is such a header
  * that its value corresponds with this one
  *
  * TODO this should extend ModeledCustomHeader so that headerValueByType directive usages become less convoluted
  */
class RealRemoteAddress (val address: InetSocketAddress) extends CustomHeader {
  override def name: String = RealRemoteAddress.name
  override def lowercaseName: String = name.toLowerCase

  override def value: String = address.toString

  // this is just a header to make connection data available in routes, it is neither in- nor outbound
  override def renderInRequests: Boolean = false
  override def renderInResponses: Boolean = false
}
