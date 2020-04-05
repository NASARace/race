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
import akka.pattern.ask
import akka.util.Timeout

/**
  * a trait that lets non-actor objects use the ask pattern in a way that the
  * recipient can verify the message came from this entity.
  * The recipient cannot just check the sender since the ask pattern makes use
  * of temporary actors.
  *
  * NOTE - this is not recursive, we can only ask one result at a time, i.e. this is a
  * synchronous construct
  */
trait VerifiableAsker {
  // private store for what we have asked
  private var _question: Option[(ActorRef,Any)] = None

  // the asker part
  def askVerifiableForResult[T] (recipient: ActorRef,msg: Any)(checkResponse: PartialFunction[Any,T])(implicit timeout:Timeout): T = {
    val future = synchronized {
      _question = Some(recipient,msg)
      recipient ? msg
    }
    askForResult(future)(checkResponse)
  }

  // the askee part
  def isVerifiedSenderOf(msg: Any)(implicit self: ActorRef) = synchronized {
    _question match {
      case Some((`self`,`msg`)) =>
        _question = None
        true
      case _ => false
    }
  }
}
