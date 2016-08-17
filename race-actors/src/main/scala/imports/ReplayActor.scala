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

package gov.nasa.race.actors.imports

import java.io.{BufferedInputStream, FileInputStream, InputStream}
import java.util.zip.GZIPInputStream

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.actors.FilteringPublisher
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core.{ContinuousTimeRaceActor, _}
import gov.nasa.race.data.{ArchiveEntry, ArchiveReader}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * RACE actor that replays a channel from a file (which should be local)
 */
class ReplayActor (val config: Config) extends ContinuousTimeRaceActor with FilteringPublisher {
  case class Replay (msg: Any)

  // everything lower than that we don't bother to schedule and publish right away
  final val SchedulerThresholdMillis: Long = 30

  val pathName = config.getString("pathname")
  val bufSize = config.getIntOrElse("buffer-size", 4096)
  var compressedMode = config.getBooleanOrElse("compressed", pathName.endsWith(".gz"))
  val rebaseDates = config.getBooleanOrElse("rebase-dates", false)
  val breakAfter = config.getIntOrElse("break-after", 20) // reschedule after at most N published messages

  val iStream = openStream
  val archiveReader = newInstance[ArchiveReader](config.getString("archive-reader"), Array(classOf[InputStream]), Array(iStream)).get
  var count = 0

  info(s"initializing replay of $pathName starting at $simTime")

  def openStream: InputStream = {
    val fis = new FileInputStream(pathName)
    if (compressedMode) new GZIPInputStream(fis,bufSize) else new BufferedInputStream(fis,bufSize)
  }

  override def onStartRaceActor(originator: ActorRef): Any = {
    super.onStartRaceActor(originator)
    if (rebaseDates) archiveReader.setBaseDate(simClock.dateTime)

    scheduleNext
  }

  override def onTerminateRaceActor(originator: ActorRef): Any = {
    super.onTerminateRaceActor(originator)
    archiveReader.close
    iStream.close
  }

  override def handleMessage: Receive = {
    case Replay(msg) =>
      publishFiltered(msg)
      scheduleNext
  }

  @tailrec final def scheduleNext: Unit = {
    if (archiveReader.hasMoreData) {
      archiveReader.read match {
        case Some(ArchiveEntry(date,msg)) =>
          // negative if date not yet reached
          val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
          if (dt > SchedulerThresholdMillis) {
            debug(f"scheduling in $dt milliseconds: $msg%30.30s.. ")
            scheduler.scheduleOnce(dt milliseconds, self, Replay(msg))
          } else {
            // <2do> we should probably check for msg expiration here
            count += 1
            if (count > breakAfter){  // reschedule this actor to avoid starving others
              count = 0
              self ! Replay(msg)
            } else { // send right away and loop
              publishFiltered(msg)
              scheduleNext  // needs to be in tailrec position
            }
          }

        case None =>
          warning(s"ignored entry from stream $pathName")
      }
    } else {
      info(s"reached end of replay stream $pathName")
      archiveReader.close // no need to keep it around
      iStream.close
    }
  }
}
