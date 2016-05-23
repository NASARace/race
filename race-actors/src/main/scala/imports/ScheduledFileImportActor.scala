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

package gov.nasa.race.actors.imports

import java.io.File

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.common.FileUtils._
import gov.nasa.race.common.FileEventScheduler
import gov.nasa.race.core.PublishingRaceActor
import com.github.nscala_time.time.Imports._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * a RaceActor that reads a XML based schedule and imports the referenced
 * file contents at the specified times
 */
class ScheduledFileImportActor (val config: Config)  extends PublishingRaceActor {

  case class ProcessEvent(event: SchedulerEvent)

  val schedule = new File(config.getString("schedule"))
  val writeTo = config.getString("write-to")

  val fileScheduler = new FileEventScheduler(publishFile)

  if (schedule.isFile){
    fileScheduler.loadSchedule(schedule)
  } else {
    log.warning(s"schedule file not found: $schedule")
  }

  override def startRaceActor(originator: ActorRef) = {
    super.startRaceActor(originator)  // <2do> - this has to translate simTime to wallTime
    fileScheduler.setTimeBase(DateTime.now)
    scheduleNext
  }

  override def handleMessage: Receive = {
    case ProcessEvent(e) =>
      e.fire()
      scheduleNext
  }

  //--- internals

  def publishFile (file: File) = {
    fileContentsAsUTF8String(file) match {
      case Some(s) => publish(writeTo, s)
      case None => log.warning(s"scheduled file not found: $file")
    }
  }

  def scheduleNext = {
    fileScheduler.getNext match {
      case Some(e) =>
        val nextEvent = e
        val delay = e.millisFromNow
        if (delay >= 0) {
          scheduler.scheduleOnce(delay.millis, self, ProcessEvent(nextEvent))
        } else {
          log.warning(s"event time has already passed: $e")
        }
      case None =>
        log.info(s"all scheduled events of $schedule processed")
    }
  }
}
