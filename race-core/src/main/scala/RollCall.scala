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

import akka.actor.ActorRef
import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}
import Messages._

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

/**
 * generic message pattern to collect responses from a set of actors.
 *
 * Each actor can either directly or through a future put in its data, when all source
 * actors have entered the data the originator is informed through a RollCallComplete message
 */
trait RollCall extends RaceSystemMessage with Serializable {

  val originator: ActorRef
  val parent: Option[RollCall]

  private[this] val lock = "ROLLCALL_LOCK"
  private[this] var requests: List[ActorRef] = Nil
  val responses: ConcurrentMap[ActorRef,Any] = TrieMap()

  def answer(entry: (ActorRef,Any)): Unit = {
    lock.synchronized {
      responses += entry

      if (isCompleted) {
        originator ! RollCallComplete(this)
      }
    }
  }

  def answer (ref: ActorRef): Unit = answer( ref->ref )

  def send(recipients: Iterable[ActorRef], timeout: FiniteDuration = 5.seconds) = {
    lock.synchronized {
      requests = Nil
      responses.clear()

      for (ref <- recipients) {
        requests = ref :: requests
        ref ! this
      }

      if (timeout != 0) {
        originator ! SetTimeout(RollCallTimeout(this), timeout)
      }
    }
  }

  def isCompleted = {
    lock.synchronized {
      (responses.size == requests.size)
    }
  }
}

// response messages
// not ideal yet - can probably be generified with TypeTags
case class RollCallComplete(rollCall: RollCall) extends RaceSystemMessage

case class RollCallTimeout(rollCall: RollCall) extends RaceSystemMessage


