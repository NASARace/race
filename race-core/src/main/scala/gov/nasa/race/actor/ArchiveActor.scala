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

package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.archive.ArchiveWriter
import gov.nasa.race.core._
import gov.nasa.race.core._

/**
  * generic actor that writes the subscribed channel to disk
  * using a configurable ArchiveWriter object for stream/file management and formatting
  */
class ArchiveActor (val config: Config) extends ChannelTopicSubscriber with ContinuousTimeRaceActor {
  var stopArchiving = false
  val writer: ArchiveWriter = createWriter

  log.info(s"$name archiving channels [$readFromAsString] to ${writer.pathName}")

  // override for hardwired writers
  // note - scala 2.12.3 can't infer getConfigurable type arg
  protected def createWriter: ArchiveWriter = getConfigurable[ArchiveWriter]("writer")

  override def onStartRaceActor (originator: ActorRef) = {
    writer.open(baseSimTime)
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    stopArchiving = true
    writer.close()
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case BusEvent(_,msg,_) =>
      if (!stopArchiving) {
        updateSimTime
        if (writer.write(simTime, msg)) {
          debug(f"$name archived $msg%40.40s")
        } else {
          debug(f"$name ignored $msg%40.40s")
        }
      }
  }
}
