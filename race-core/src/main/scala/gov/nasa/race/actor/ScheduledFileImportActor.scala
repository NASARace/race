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

import java.io.File

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.core.PublishingRaceActor
import gov.nasa.race.schedule.{FileEventScheduler, SchedulerEvent}
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.uom.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * a RaceActor that reads an XML based schedule and imports the referenced
  * file contents at the specified wall-clock times
  *
  * TODO - this should be extended to sim clock time
 */
class ScheduledFileImportActor (val config: Config)  extends PublishingRaceActor {

  case class ProcessEvent(event: SchedulerEvent)

  val schedule = new File(config.getString("schedule"))
  val fileScheduler = new FileEventScheduler(publishFile)

  if (schedule.isFile){
    fileScheduler.loadSchedule(schedule)
  } else {
    warning(s"schedule file not found: $schedule")
  }

  override def onStartRaceActor(originator: ActorRef) = {
    fileScheduler.setTimeBase(DateTime.now)
    scheduleNext
    super.onStartRaceActor(originator)  // <2do> - this has to translate simTime to wallTime
  }

  override def handleMessage: Receive = {
    case ProcessEvent(e) =>
      e.fire()
      scheduleNext
  }

  //--- internals

  def publishFile (file: File) = {
    fileContentsAsUTF8String(file) match {
      case Some(s) => publish(s)
      case None => warning(s"scheduled file not found: $file")
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
          warning(s"event time has already passed: $e ($delay msec)")
        }
      case None =>
        info(s"all scheduled events of $schedule processed")
    }
  }
}
