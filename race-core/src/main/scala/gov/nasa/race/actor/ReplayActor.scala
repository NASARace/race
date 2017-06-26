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

import java.io.{BufferedInputStream, FileInputStream, InputStream}
import java.util.zip.GZIPInputStream

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.Counter
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ClockAdjuster, ContinuousTimeRaceActor}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * RACE actor that replays a channel from a file (which should be local)
  *
  * The underlying model is that archives contain a irregular time series of messages, i.e. time is
  * strictly monotonic (increasing). We do support to start replaying messages inside the stream, i.e.
  * skipping over a number of messages at the beginning. Once we start, there is no more skipping, all messages
  * will be injected. The number of initial skips is bounded, once we exceed this limit the actor considers
  * startup to be failed
  *
  * The rationale behind this model is that start time is usually non-deterministic, but replay from a given (time)
  * starting point should be deterministic, which means we should not loose messages during replay.
  *
  * ReplayActor is a potential ClockAdjuster, i.e. if enabled the global sim clock can be reset to the first
  * message time encountered. The user has to make sure there is no force-fight in the configuration of several ReplayActors
  */
class ReplayActor (val config: Config) extends ContinuousTimeRaceActor
                                       with FilteringPublisher with Counter with ClockAdjuster {
  case class Replay (msg: Any)

  // everything lower than that we don't bother to schedule and publish right away
  final val SchedulerThresholdMillis: Long = 30

  val pathName = config.getString("pathname")
  val bufSize = config.getIntOrElse("buffer-size", 8192)
  var compressedMode = config.getBooleanOrElse("compressed", pathName.endsWith(".gz"))
  val rebaseDates = config.getBooleanOrElse("rebase-dates", false)
  val counterThreshold = config.getIntOrElse("break-after", 20) // reschedule after at most N published messages
  val skipThresholdMillis = config.getIntOrElse("skip-millis", 1000) // skip until current sim time - replay time is within limit
  val maxSkip = config.getIntOrElse("max-skip", 1000) // stop replay if we hit more than max-skip consecutive malformed entries

  val iStream = openStream
  val archiveReader = newInstance[ArchiveReader](config.getString("archive-reader"), Array(classOf[InputStream]), Array(iStream)).get

  info(s"initializing replay of $pathName starting at $simTime")

  def openStream: InputStream = {
    val fis = new FileInputStream(pathName)
    if (compressedMode) new GZIPInputStream(fis,bufSize) else new BufferedInputStream(fis,bufSize)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    if (rebaseDates) archiveReader.setBaseDate(simClock.dateTime)
    if (scheduleFirst(0)) super.onStartRaceActor(originator) else false
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    archiveReader.close
    iStream.close
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = handleReplayMessage

  def handleReplayMessage: Receive = {
    case Replay(msg) =>
      if (msg != None) publishFiltered(msg)
      scheduleNext(0)
  }

  def replayMessageLater(msg: Any, dtMillis: Long) = {
    debug(f"scheduling in $dtMillis milliseconds: $msg%30.30s.. ")
    scheduler.scheduleOnce(dtMillis milliseconds, self, Replay(msg))
  }

  def replayMessageNow(msg: Any) = {
    if (incCounter) {
      debug(f"publishing now: $msg%30.30s.. ")
      publishFiltered(msg)
      scheduleNext(0)
    } else {
      self ! Replay(msg)
    }
  }

  def reachedEndOfArchive = {
    info(s"reached end of replay stream $pathName")
    archiveReader.close // no need to keep it around
    iStream.close
  }

  @tailrec final def scheduleFirst (skipped: Int): Boolean = {
    if (archiveReader.hasMoreData) {
      archiveReader.read match {
        case Some(ArchiveEntry(date, msg)) =>
          checkInitialClockReset(date) // if enabled this might reset the clock on the initial check

          if (exceedsEndTime(date)){
            info(s"first message exceeds configured end-time")
            false

          } else {
            val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
            if (dt > SchedulerThresholdMillis) { // far enough in the future to be scheduled
              replayMessageLater(msg, dt)
              true

            } else { // now, or has already passed
              if (-dt > skipThresholdMillis) { // outside replay time window
                scheduleFirst(skipped + 1)
              } else {
                info(s"skipping first $skipped messages")
                replayMessageNow(msg)
                true
              }
            }
          }
        case None => // not a valid entry - this is a safeguard against very large files with broken content
          if (skipped < maxSkip) {
            scheduleFirst(skipped + 1)
          } else {
            warning(s"maximum number of malformed entries exceeded")
            false
          }
      }
    } else {  // nothing to replay left
      reachedEndOfArchive
      false
    }
  }

  @tailrec final def scheduleNext (skipped: Int=0): Unit = {
    if (archiveReader.hasMoreData) {
      archiveReader.read match {
        case Some(ArchiveEntry(date,msg)) =>
          if (!exceedsEndTime(date)) {
            val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
            if (dt > SchedulerThresholdMillis) { // schedule - message date is far enough in future
              replayMessageLater(msg, dt)
            } else { // message date is too close or has already passed (note we don't skip messages here)
              replayMessageNow(msg)
            }
          }

        case None =>
          if (skipped < maxSkip) {
            warning(s"ignored entry from stream $pathName")
            if (incCounter) scheduleNext(skipped + 1) else self ! Replay(None)
          } else {
            warning(s"maximum number of consecutive malformed entries exceeded during replay")
          }
      }
    } else reachedEndOfArchive
  }
}
