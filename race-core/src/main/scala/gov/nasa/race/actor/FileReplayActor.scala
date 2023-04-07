/*
 * Copyright (c) 2023, United States Government, as represented by the
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

import akka.actor.{ActorRef, Cancellable}
import gov.nasa.race.common.{FileAvailable, UnixPath}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{ContinuousTimeRaceActor, PublishingRaceActor}
import gov.nasa.race.uom.Time
import gov.nasa.race.uom.Time.Hours
import gov.nasa.race.util.FileUtils

import java.io.File
import java.nio.file.PathMatcher
import scala.annotation.tailrec

/**
 * a RaceActor that finds matching files in a configured directory, associates them with replay timestamps
 * and publishes file availability events accordingly
 *
 * TODO implement pause/resume
 */
trait FileReplayActor [T <:FileAvailable] extends ContinuousTimeRaceActor with PublishingRaceActor {

  case class ReplayFile (fe: T) // this should be a path dependent type - we don't want to replay anything we didn't schedule ourselves

  val dir: File = config.getExistingDir("directory")
  val maxAge: Time = config.getDurationTimeOrElse("max-age", Hours(4))

  protected var fileAvailableSequence: Iterator[T] = Iterator.empty
  protected var nextEntry: Option[T] = None
  protected var pending: Option[Cancellable] = None

  //--- override if we have a hardcoded file matching or sequencing mechanism (e.g. by creating files)
  protected def sequenceFileAvailables(): Iterator[T] = {
    val fileList = FileUtils.getMatchingFilesIn(dir.getPath, getPathMatcher())
    fileList.flatMap( getFileAvailable).sortWith(isFileAvailableEarlier).iterator
  }
  protected def getPathMatcher(): PathMatcher =  UnixPath.regexMatcher(config.getString("file-pattern"))
  protected def isFileAvailableEarlier (a: T, b: T): Boolean = a.date < b.date

  //--- to be provided by concrete type
  protected def getFileAvailable (f: File) : Option[T] // to be provided by concrete type
  protected def publishFileAvailable(fe: T): Unit // to be provided by concrete type

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator)
    fileAvailableSequence = sequenceFileAvailables()
    scheduleNext()
    true
  }

  override def handleMessage: Receive = handleFileReplayMessage orElse super.handleMessage

  def handleFileReplayMessage: Receive = {
    case ReplayFile(e) =>
      publishFileAvailable(e)
      scheduleNext()
  }

  @tailrec final def scheduleNext(): Unit = {
    if (fileAvailableSequence.hasNext) {
      val e = fileAvailableSequence.next()

      if (e.date <= currentSimTime) {
        if (simTime.timeSince(e.date) < maxAge) publishFileAvailable( e)
        scheduleNext()

      } else {
        val dtWall = (simTime.timeUntil(e.date) / timeScale).toFiniteDuration
        nextEntry = Some(e)
        pending = Some(scheduleOnce( dtWall, ReplayFile(e)))
      }
    } else {
      info(s"no more file in $dir to replay")
    }
  }
}
