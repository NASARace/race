/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.common

import akka.actor.ActorRef
import gov.nasa.race.uom.{DateTime, Time}

import java.util.concurrent.atomic.AtomicBoolean

/**
  * thread that can be used to separate background tasks such as network or disk I/O
  *
  * note this is a class and not a trait - we want to force this to be a separate object
  */
abstract class DataAcquisitionThread extends Thread with LogWriter {
  protected var isDone: AtomicBoolean = new AtomicBoolean(false)

  setDaemon(true)

  /**
    * this can be called from the outside and has to be thread safe
    */
  def terminate(): Unit = {
    info(s"terminating data acquisition thread $getName")
    isDone.set(true)
    //if (isAlive) interrupt()  // we should not need this - it's a daemon
  }
}

/**
  * a DataAcquisitionThread mixin that periodically polls something
  */
trait PollingDataAcquisitionThread extends DataAcquisitionThread {
  val pollingInterval: Time
  protected def poll(): Unit // the actual IO, to be provided by subtype

  protected var lastPoll = DateTime.UndefinedDateTime

  protected def runPollingLoop(): Unit = {
    info(s"started polling data acquisition thread $getName")
    while (!isDone.get()) {
      lastPoll = DateTime.now

      // NOTE this is a catch-all - if polling should stop concrete type should do its own handling and use isDone to terminate
      try {
        poll()
      } catch {
        case x: Throwable => warning(s"data acquisition thread polling cycle failed with: $x")
      }
      sleepForRemainder()
    }
    info(s"data acquisition thread $getName terminated")
  }

  override def run(): Unit = runPollingLoop()

  protected def sleepForRemainder(): Unit = {
    val remainingMillis = (pollingInterval - DateTime.now.timeSince(lastPoll)).toMillis

    if (remainingMillis > 10) { // otherwise we don't bother to sleep
      try {
        Thread.sleep(remainingMillis)
      } catch {
        case _: InterruptedException => // ignore
      }
    }
  }
}

/**
  * a DataAcquisitionThread that is used from within a RaceActor context
  *
  * note that we go to great lengths to minimize exposure of actor details so that sub-types do not
  * accidentally introduce race conditions.
  *
  * Communication between thread and actor should be uni-directional through messages, the I/O thread is the
  * producer and the actor the consumer.
  */
abstract class ActorDataAcquisitionThread (client: ActorRef) extends DataAcquisitionThread {
  // note that msg is the payload and should be immutable
  protected def sendToClient (msg: Any): Unit = client ! msg
}