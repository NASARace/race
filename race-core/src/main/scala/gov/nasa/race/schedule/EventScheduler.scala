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

package gov.nasa.race.schedule

import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.ThreadUtils.Signal

import scala.collection.mutable

abstract class SchedulerEvent (action: =>Any) {
  def fire() = action
}

class RelSchedulerEvent (val after: Time, action: => Any) extends SchedulerEvent(action) {
  def toAbsSchedulerEvent (baseDate: DateTime) = new AbsSchedulerEvent(baseDate + after, action)

  override def toString = s"RelSchedulerEvent $after"
}

class AbsSchedulerEvent (val when: DateTime, action: => Any) extends SchedulerEvent(action) {
  override def toString = s"AbsSchedulerEvent $when"

  def millisFromNow: Long = {
    val tNow = DateTime.now
    tNow.timeUntil(when).toMillis
  }
}

object SchedulerEvent {
  def after(after: Time)(action: => Any) = new RelSchedulerEvent(after,action)
  def at(when: DateTime)(action: => Any) = new AbsSchedulerEvent(when,action)

  implicit val relOrdering = new Ordering[RelSchedulerEvent] {
    def compare (a: RelSchedulerEvent, b: RelSchedulerEvent): Int = b.after.compare(a.after)
  }
  implicit val absOrdering = new Ordering[AbsSchedulerEvent] {
    def compare (a: AbsSchedulerEvent, b: AbsSchedulerEvent): Int = b.when.compare(a.when)
  }
}

/**
  * a thread based scheduler
  *
  * We don't use futures or Akka scheduling here since we don't want
  * to burn a pooled thread on something that waits/sleeps 99% of the time, and
  * we need something that also works outside of actors
  * This is what Java'ish Scala looks like
  *
  * TODO - this should support sim time
 */
trait EventScheduler {
  private val lock = new Signal // we can't sync on 'queue' because its a var, and 'this' is public
  private val completionSignal = new Signal

  @volatile private var thread: Thread = _
  @volatile var terminate: Boolean = false

  // this is just a temp queue that is merged into the absQueue once we process
  private val relQueue = new mutable.PriorityQueue[RelSchedulerEvent]()

  // the queue that will be processed
  private val absQueue = new mutable.PriorityQueue[AbsSchedulerEvent]()

  def scheduleAfter (after: Time)(action: =>Any): Unit = scheduleEvent( SchedulerEvent.after(after)(action))

  def scheduleEvent (newEvent: RelSchedulerEvent): Unit = {
    lock.synchronized {
      if (thread != null){
        absQueue += newEvent.toAbsSchedulerEvent(DateTime.now)
        lock.notify() // schedule thread already running
      } else {
        relQueue += newEvent
      }
    }
  }

  def scheduleRelEvents (newEvents: Seq[RelSchedulerEvent]) = {
    lock.synchronized {
      if (thread != null){
        val now = DateTime.now
        for (re <- newEvents) absQueue += re.toAbsSchedulerEvent(now)
        lock.notify()
      } else {
        relQueue ++= newEvents
      }
    }
  }

  def scheduleAt (when: DateTime)(action: => Any): Unit = scheduleEvent( SchedulerEvent.at(when)(action))

  def scheduleEvent (newEvent: AbsSchedulerEvent) = {
    lock.synchronized {
      absQueue += newEvent
      if (thread != null) lock.notify
    }
  }

  def scheduleAbsEvents (newEvents: Seq[AbsSchedulerEvent]) = {
    lock.synchronized {
      absQueue ++= newEvents
      if (thread != null) lock.notify
    }
  }

  def purgeEvents = {
    lock.synchronized {
      relQueue.clear()
      absQueue.clear()
    }
  }


  private def processNextEvent() = {
    try {
      val nextEvent = absQueue.dequeue()
      val delay = nextEvent.millisFromNow
      if (delay >= 0) {
        if (delay > 0)
          Thread.sleep(delay)
        nextEvent.fire()
      }
    } catch {
      case x: NoSuchElementException =>
    }
  }

  def getNext: Option[AbsSchedulerEvent] = {
    lock.synchronized {
      try {
        Some(absQueue.dequeue())
      } catch {
        case x: NoSuchElementException => None
      }
    }
  }

  def setTimeBase (baseDate: DateTime): Unit = {
    mergeInRelQueue(baseDate)
  }

  protected def mergeInRelQueue (baseDate: DateTime) = {
    for (re <- relQueue) {
      absQueue += re.toAbsSchedulerEvent(baseDate)
    }
    relQueue.clear()
  }

  def processEventsSync (baseDate: DateTime) = {
    lock.synchronized {
      mergeInRelQueue(baseDate)
      while (!absQueue.isEmpty) {
        processNextEvent()
      }
    }
  }

  def processEventsAsync (baseDate: DateTime, keepAlive: Boolean = false) = {
    lock.synchronized {
      if (thread == null) { // don't restart running thread
        thread = new Thread("scheduler") {
          override def run: Unit = {
            while (!terminate) {
              lock.synchronized {
                if (absQueue.isEmpty) {
                  if (keepAlive){
                    completionSignal.signal()
                    lock.wait(10000)
                  } else {
                    terminate = true
                  }
                }
              }
              processNextEvent()
            }
            thread = null
            completionSignal.signal()
          }
        }
        thread.setDaemon(true)
        thread.start()
      }
    }
  }

  /**
   * DANGER - this is blocking. Use future if caller can't be blocked
   */
  def waitForCompletion (timeout: Int = 0) = {
    if (thread != null) {
      completionSignal.wait(timeout)
    }
  }
}
