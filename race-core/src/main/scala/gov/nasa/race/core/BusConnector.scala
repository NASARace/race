/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.core

import akka.actor.{Actor, ActorLogging}
import gov.nasa.race.core.{RemoteSubscribe, RemoteUnsubscribe}

/**
  * a RACE specific system actor that serves as an interface for external actor clients which cannot
  * directly publish to the bus since it is not serializable. Note that sending to remote
  * subscribers does work, i.e. we don't have to relay outgoing messages
  *
  * BusConnectors are automatically created, either per remote actor or as a shared resource. The
  * latter might help to increase throughput by saving context switches, but might also lead to
  * bottlenecks if there is a high volume of external messages
  */
class BusConnector (val bus: Bus) extends Actor with ActorLogging {
  def receive = {
    case RemoteSubscribe(remoteActor,channel) =>
      bus.subscribe(remoteActor,channel)

    case RemoteUnsubscribe(remoteActor,channel) =>
      bus.unsubscribe(remoteActor, channel)

    case e: BusEvent => // the remotely published messages
      bus.publish(e)
  }
}
