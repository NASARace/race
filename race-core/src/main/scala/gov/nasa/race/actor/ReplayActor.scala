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
import gov.nasa.race.archive.ArchiveReader
import gov.nasa.race.common.Counter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ClockAdjuster, ContinuousTimeRaceActor}
import gov.nasa.race.uom.DateTime

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
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
  case class Replay (msg: Any, date: DateTime)
  case object ScheduleNext

  // everything lower than that we don't bother to schedule and publish right away
  final val SchedulerThresholdMillis: Long = 30

  val rebaseDates = config.getBooleanOrElse("rebase-dates", false)
  val counterThreshold = config.getIntOrElse("break-after", 20) // reschedule after at most N published messages
  val skipThresholdMillis = config.getIntOrElse("skip-millis", 1000) // skip until current sim time - replay time is within limit
  val maxSkip = config.getIntOrElse("max-skip", 1000) // stop replay if we hit more than max-skip consecutive malformed entries

  val reader: ArchiveReader = createReader
  var noMoreData = !reader.hasMoreData

  var isFirst = true // we haven't scheduled or replayed anything yet
  val pendingMsgs = new ListBuffer[Replay]  // replay messages received in stopped state  that have to be re-scheduled

  /** override for hardwired readers */
  def createReader: ArchiveReader = getConfigurable[ArchiveReader]("reader") // note 2.12.3 can't infer type arg

  if (noMoreData) {
    warning(s"no data for ${reader.pathName}")
  } else {
    info(s"initializing replay of ${reader.pathName} starting at $simTime")
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (rebaseDates) reader.setBaseDate(simClock.dateTime)
    super.onStartRaceActor(originator) && scheduleFirst(0)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    reader.close
    noMoreData = true
    super.onTerminateRaceActor(originator)
  }

  override def onSyncWithRaceClock = {
    checkResume(isStopped)
    super.onSyncWithRaceClock
  }

  override def onPauseRaceActor (originator: ActorRef) = {
    info(s"pausing..")
    super.onPauseRaceActor(originator)
  }

  override def onResumeRaceActor (originator: ActorRef) = {
    info(s"replay resuming with ${pendingMsgs.size} pending messages..")
    // note the clock is still stopped when we get this
    checkResume(false)
    super.onResumeRaceActor(originator)
  }

  override def handleMessage = handleReplayMessage

  def handleReplayMessage: Receive = {
    case r@Replay(msg,date) =>
      val dtMillis = date.toMillis - updatedSimTimeMillis
      if (dtMillis < SchedulerThresholdMillis) { // this includes times that already have passed
        debug(f"publishing scheduled: $msg%30.30s.. ")
        publishFiltered(msg)
        scheduleNext(0)

      } else { // we were paused or scaled down since schedule
        if (!isStopped) {
          debug(f"re-scheduling in $dtMillis milliseconds: $msg%30.30s.. ")
          scheduler.scheduleOnce(dtMillis milliseconds, self, r)
        } else {
          // we are stopped - make sure we schedule this again once we resume
          debug(f"queue pending $msg%30.30s.. ")
          pendingMsgs += r
        }
      }

    case ScheduleNext => if (!isStopped) scheduleNext(0)
  }

  def replayMessageLater(msg: Any, dtMillis: Long, date: DateTime) = {
    debug(f"scheduling in $dtMillis milliseconds: $msg%50.50s.. ")
    scheduler.scheduleOnce(dtMillis milliseconds, self, Replay(msg,date))
  }

  def replayMessageNow(msg: Any, date: DateTime) = {
    if (incCounter) {
      debug(f"publishing now: $msg%50.50s.. ")
      publishFiltered(msg)
      scheduleNext(0)
    } else {
      self ! Replay(msg,date)
    }
  }

  def reachedEndOfArchive = {
    info(s"reached end of replay stream ${reader.pathName}")
    reader.close  // no need to keep it around
    noMoreData = true
  }

  @tailrec final def scheduleFirst (skipped: Int): Boolean = {

    reader.readNextEntry match {
      case Some(e) =>
        val date = e.date
        val msg = e.msg
        checkInitialClockReset(date)

        if (exceedsEndTime(date)) {
          info(s"first message exceeds configured end-time")
          false

        } else {
          val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
          if (dt > SchedulerThresholdMillis) { // far enough in the future to be scheduled
            isFirst = false
            replayMessageLater(msg, dt, date)
            true

          } else { // now, or has already passed
            if (-dt > skipThresholdMillis) { // outside replay time window
              scheduleFirst(skipped + 1) // skip this message
            } else {
              info(s"skipping first $skipped messages")
              isFirst = false
              replayMessageNow(msg, date)
              true
            }
          }
        }

      case None => // no more archived messages
        reachedEndOfArchive
        false
    }
  }

  def scheduleNext (skipped: Int=0): Unit = {
    if (!noMoreData) {
      reader.readNextEntry match {
        case Some(e) =>
          val date = e.date
          val msg = e.msg
          if (!exceedsEndTime(date)) {
            val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
            if (dt > SchedulerThresholdMillis) { // schedule - message date is far enough in future
              replayMessageLater(msg, dt, date)
            } else { // message date is too close or has already passed (note we don't skip messages here)
              replayMessageNow(msg, date)
            }
          }
        case None => reachedEndOfArchive
      }
    }
  }

  def checkResume (clockStopped: Boolean): Unit = {
    if (!clockStopped && !isFirst) {
      var didSchedule = false
      if (pendingMsgs.nonEmpty) {
        pendingMsgs.foreach { r =>
          val dtMillis = r.date.toMillis - updatedSimTimeMillis
          if (dtMillis < SchedulerThresholdMillis) {
            publishFiltered(r.msg)
          } else {
            scheduler.scheduleOnce(dtMillis milliseconds, self, r)
            didSchedule = true
          }
        }
        pendingMsgs.clear
      }
      if (!didSchedule) {
        // we didn't re-schedule a sent message - schedule the next one
        scheduleNext(0)
      }
    }
  }
}
