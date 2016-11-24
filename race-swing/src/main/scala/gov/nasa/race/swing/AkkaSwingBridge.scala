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

package gov.nasa.race.swing

import java.awt.EventQueue

import akka.actor.Actor._

/**
  * a mixin that does safe transition from Akka to Swing threads, to make sure
  * messages get processed within the EDT
  *
  * Instances of this trait are supposed to execute in the EDT, i.e. this is
  * a mixin for components etc.
  */
trait AkkaSwingBridge {
  var queue = List.empty[Any] // this has to be processed in reverse
  var queueEmpty = true // signals that we need to invoke the EventDispatcher

  val queueProcessor = new Runnable {
    // executed from EventDispatchThread
    override def run: Unit = {
      queueEmpty = true
      val q = queue
      queue = List.empty[Any]

      for (m <- q.reverseIterator) handleMessage(m) // don't hold a lock while processing
    }
  }

  // executed from Akka thread. Make sure we don't miss a queue reset due
  // to race conditions.
  // Note this is executed more often than queueProcessor.run(), hence this should
  // be faster, and the FIFO processing should be left to the processor
  def queueMessage (msg: Any) = {
    queue = msg :: queue // this means queue has to be processed in reverse
    if (queueEmpty) {
      queueEmpty = false
      EventQueue.invokeLater(queueProcessor)
    }
  }

  /**
    *  this has to be implemented in the concrete RaceLayer. It is executed in the
    *  event dispatcher
    */
  def handleMessage: Receive

}
