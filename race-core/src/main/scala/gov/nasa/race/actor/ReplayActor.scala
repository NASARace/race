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
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
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
  * Note that is is essential that we only call scheduleNext when the previous entry is published or otherwise
  * we might loose the total order over published entries (potentially resulting in out-of-order messages)
  *
  * A Replayer is a potential ClockAdjuster, i.e. if enabled the global sim clock can be reset to the first
  * message time encountered. The user has to make sure there is no force-fight in the configuration of several ReplayActors
  */
trait Replayer extends ContinuousTimeRaceActor with FilteringPublisher with Counter with ClockAdjuster {
  type R <: ArchiveReader // make this a type member so that concrete Replayers can use inner classes

  case class Replay (msg: Any, date: DateTime)
  case object ScheduleNext

  // everything lower than that we don't bother to schedule and publish right away
  final val SchedulerThresholdMillis: Long = 30

  val rebaseDates = config.getBooleanOrElse("rebase-dates", false) // rebase dates with respect to sim time
  val counterThreshold = config.getIntOrElse("break-after", 1000) // reschedule after at most N published messages - NOTE this can break monotonicity
  val skipThresholdMillis = config.getIntOrElse("skip-millis", 1000) // skip until current sim time - replay time is within limit
  val maxSkip = config.getIntOrElse("max-skip", 1000) // stop replay if we hit more than max-skip consecutive malformed entries

  val flatten: Boolean = config.getBooleanOrElse("flatten", false) // do we publish collection results separately

  val reader: R = createReader
  var noMoreData = !reader.hasMoreArchivedData

  var isFirst = true // we haven't scheduled or replayed anything yet
  val pendingMsgs = new ListBuffer[Replay]  // replay messages received in stopped state  that have to be re-scheduled
  var nScheduled = 0

  def createReader: R  // to be provided by concrete type

  if (noMoreData) {
    warning(s"no data for ${reader.pathName}")
  } else {
    info(s"initializing replay of ${reader.pathName} starting at $simTime")
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (rebaseDates) {
      reader.setBaseDate(simClock.dateTime)
      val off = config.getOptionalFiniteDuration("rebase-offset")
      if (off.isDefined) reader.setDateOffsetMillis(off.get.toMillis)
    }
    super.onStartRaceActor(originator) && scheduleFirst(0)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    reader.close()
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
      nScheduled -= 1
      val dtMillis = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date)) //date.toEpochMillis - updatedSimTimeMillis
      if (dtMillis < SchedulerThresholdMillis) { // this includes times that already have passed
        replay(msg)
        scheduleNext(0)

      } else { // we were paused or scaled down since schedule
        if (!isStopped) {
          debug(f"re-scheduling in $dtMillis milliseconds: $msg%30.30s.. ")
          nScheduled += 1
          scheduler.scheduleOnce(dtMillis milliseconds, self, r)
        } else {
          // we are stopped - make sure we schedule this again once we resume
          debug(f"queue pending $msg%30.30s.. ")
          pendingMsgs += r
        }
      }

    case ScheduleNext => if (!isStopped) scheduleNext(0)
  }

  // override if we have to do any pre-processing before publishing
  protected def replay (msg: Any): Unit = {
    debug(f"publishing $msg%30.30s.. ")
    publishFiltered(msg)
  }

  def replayMessageLater(msg: Any, dtMillis: Long, date: DateTime) = {
    debug(f"scheduling in $dtMillis milliseconds: $msg%50.50s.. ")
    nScheduled += 1
    scheduler.scheduleOnce(dtMillis milliseconds, self, Replay(msg,date))
  }

  override def publishFiltered(msg: Any): Unit = {
    if (flatten){
      msg match {
        case list: Iterable[_] => list.foreach( m=> super.publishFiltered(m))
        case m => super.publishFiltered(m)
      }
    } else super.publishFiltered(msg)
  }

  def replayMessageNow(msg: Any, date: DateTime): Boolean = {
    if (incCounter) {
      replay(msg)
      true // caller should do scheduleNext

    } else { // avoid starvation by re-scheduling after a max counter has been reached
      nScheduled += 1
      self ! Replay(msg,date)
      false // caller should stop
    }
  }

  def reachedEndOfArchive = {
    warning(s"reached end of replay stream ${reader.pathName}")
    reader.close()  // no need to keep it around
    noMoreData = true
  }

  // override if we need to initialize the reader before calling readNextEntry()
  protected def readNextEntry(): Option[ArchiveEntry] = {
    reader.readNextEntry()
  }

  @tailrec final def scheduleFirst (skipped: Int): Boolean = {
    readNextEntry() match {
      case Some(e) =>
        val date = e.date
        val msg = e.msg
        checkInitialClockReset(date)

        if (exceedsEndTime(date)) { // past configured end-time, we are done here
          warning(s"first message exceeds configured end-time")
          false

        } else {
          if (skipEntry(e)) {
            entrySkipped(e)
            scheduleFirst(skipped + 1)

          } else {
            val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
            if (dt > SchedulerThresholdMillis) { // far enough in the future to be scheduled
              isFirst = false
              replayMessageLater(firstMessage(msg), dt, date)
              true
            } else {
              info(s"skipping first $skipped messages")
              isFirst = false
              if (replayMessageNow( firstMessage(msg), date)) scheduleNext(0)
              true
            }
          }
        }

      case None => // no more archived messages
        reachedEndOfArchive
        false
    }
  }

  // override if we need to include some history
  protected def skipEntry (e: ArchiveEntry): Boolean = {
    e.date < baseSimTime
  }

  // override if we need to keep track of skipped entries
  protected def entrySkipped(e: ArchiveEntry): Unit = {}

  // override if we need to modify the first message to provide context, e.g. to include skipped data
  protected def firstMessage (msg: Any): Any = msg

  // this should only be called after publishing the previous entry to guarantee that we process entries in order
  @tailrec final def scheduleNext (skipped: Int=0): Unit = {
    if (!noMoreData) {
      readNextEntry() match {
        case Some(e) =>
          val date = e.date
          val msg = e.msg
          if (!exceedsEndTime(date)) {
            val dt = toWallTimeMillis(-updateElapsedSimTimeMillisSince(date))
            if (dt > SchedulerThresholdMillis) { // schedule - message date is far enough in future
              replayMessageLater(msg, dt, date)
            } else { // message date is too close or has already passed (note we don't skip messages here)
              if (replayMessageNow(msg, date)) scheduleNext(0)
            }
          }
        case None => reachedEndOfArchive
      }
    }
  }

  // be aware this is also called after a clock reset (which might come from us)
  def checkResume (clockStopped: Boolean): Unit = {
    if (!clockStopped && !isFirst) {
      if (pendingMsgs.nonEmpty) {
        pendingMsgs.foreach { r =>
          val dtMillis = toWallTimeMillis(-updateElapsedSimTimeMillisSince(r.date))
          if (dtMillis < SchedulerThresholdMillis) {
            publishFiltered(r.msg)
          } else {
            nScheduled += 1
            scheduler.scheduleOnce(dtMillis milliseconds, self, r)
          }
        }
        pendingMsgs.clear()
      }
      if (nScheduled == 0) {
        // we didn't re-schedule a sent message - schedule the next one if there is nothing pending
        // note that we can get races resulting in out-of-order messages if we schedule while there are already
        // messages scheduled
        scheduleNext(0)
      }
    }
  }
}

/**
  * generic Replayer that gets configured with an ArchiveReader
  */
class ReplayActor (val config: Config) extends Replayer {
  type R = ArchiveReader
  override def createReader: ArchiveReader = getConfigurable[ArchiveReader]("reader") // note 2.12.3 can't infer type arg
}
