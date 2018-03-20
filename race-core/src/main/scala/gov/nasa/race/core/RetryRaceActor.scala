/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import scala.collection.immutable.Queue

/**
  * a RaceActor that can re-schedule messages without changing the order in which they are processed
  *
  * this trait can be used if message processing is subject to preconditions, and processing should preserve the
  * order in which messages were received
  */
trait RetryRaceActor extends RaceActor {

  case object Retry

  var pending: Queue[Any] = Queue.empty

  def retry (msg: Any) = {
    pending = msg +: pending
    self ! Retry // we have to make sure our handleMessage gets called even if there are no other messages in the mailbox
  }

  def retryLast (msg: Any) = {
    pending = pending :+ msg
    self ! Retry // we have to make sure our handleMessage gets called even if there are no other messages in the mailbox
  }

  /**
    * mix this into the handleMessage of the concrete type like
    * {{{
    *   override def handleMessage: Receive = handleRetryMessage orElse ...
    * }}}
    */
  def handleRetryMessage: Receive = {
    case msg if pending.nonEmpty =>
      val oldMsg = pending.head
      pending = pending.drop(1)
      handleMessage.apply(oldMsg)
      if (msg != Retry){
        if (pending.nonEmpty) retryLast(msg) else retry(msg)
      }
  }
}
