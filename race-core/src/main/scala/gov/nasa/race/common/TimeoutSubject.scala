/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import gov.nasa.race.uom.{DateTime, Time}

import java.util.concurrent.{Executors, ScheduledFuture}
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.collection.mutable.{Set,Map}


/**
  * mix-in type for objects that can expire and need to be notified of it (asynchronously)
  */
trait TimeoutSubject {
  private var lastActive: DateTime = DateTime.now

  /**
    * the duration after which the timeout is expired
    * to be provided by concrete type
    */
  def timeout: Time

  /**
    * the callback action to be performed if this object timed out
    *
    * NOTE - this gets executed in the TimeoutQueue thread and hence the client has to make sure it is
    * properly synchronized to avoid data races
    */
  def timeoutExpired(): Unit

  def hasExpired: Boolean = DateTime.timeSince(lastActive) >= timeout // be aware that timer precision might cause 0 differences
  def resetExpiration(): Unit = lastActive = DateTime.now

  def timeRemaining: Time = timeout - DateTime.timeSince(lastActive)
}


/**
  * a timeout monitor that separately schedules each TimeoutSubject deadline
  *
  * subjects are only added/scheduled if they have not already expired (in which case they are still notified)
  * each subject is notified at most once (i.e. removed from the monitor when it expires)
  *
  * use this for large, irregular timeouts and few TimeoutSubjects, to avoid frequent timeout thread invocations
  */
trait IndividualTimeoutMonitor[K,V <:TimeoutSubject] {

  case class ExpirationChecker (key: K, subject: V) extends Runnable {
    var future: ScheduledFuture[_] = null

    override def run(): Unit = {
      if (subject.hasExpired) {
        timeoutSubjects -= key
        subject.timeoutExpired()
      }
    }
  }

  protected val timeoutSubjects: Map[K, ExpirationChecker] = Map.empty

  protected val scheduler = Executors.newScheduledThreadPool(1)
  protected val lock: AnyRef = new Object

  def size: Int = timeoutSubjects.size
  def isEmpty: Boolean = timeoutSubjects.isEmpty

  protected def addOrUpdate (k: K, v: V): Unit = lock.synchronized {
    if (!v.hasExpired) {
      timeoutSubjects.get(k) match {
        case Some(ec) => // there already was one scheduled - cancel and reschedule
          ec.future.cancel(false)
          ec.future = scheduler.schedule(ec, v.timeRemaining.toMillis, MILLISECONDS)
        case None => // new one
          val ec = ExpirationChecker(k,v)
          ec.future = scheduler.schedule(ec, v.timeRemaining.toMillis, MILLISECONDS)
          timeoutSubjects += (k -> ec)
      }
    } else {
      v.timeoutExpired()
    }
  }

  def -= (ts: V): Unit = lock.synchronized {
    timeoutSubjects.foreach { e=>
      val ec = e._2
      if (ec.subject == ts) {
        ec.future.cancel(false)
        timeoutSubjects -= e._1
      }
    }
  }

  def clear(): Unit = lock.synchronized {
    timeoutSubjects.foreach { _._2.future.cancel(false) }
    timeoutSubjects.clear()
  }

  def terminate(): Unit = lock.synchronized {
    clear()
    scheduler.shutdown()
  }
}

/**
  * an IndividualTimeoutMonitor that keeps TimeoutSubjects in a map
  */
class IndividualTimeoutMap[K,V <:TimeoutSubject] extends IndividualTimeoutMonitor[K,V] {

  def contains (key: K): Boolean = lock.synchronized { timeoutSubjects.contains(key) }
  def apply (key: K): V = lock.synchronized { timeoutSubjects(key).subject }
  def get (key: K): Option[V] = lock.synchronized { timeoutSubjects.get(key).map(_.subject) }
  def foreach[U] (f: ((K,V))=>U) = lock.synchronized { timeoutSubjects.foreach( e=> f(e._1, e._2.subject)) }

  def += (e: (K,V)): Unit = lock.synchronized {
    addOrUpdate(e._1,e._2)
  }

  def -= (key: K): Unit = lock.synchronized {
    timeoutSubjects.get(key) match {
      case Some(ec) =>
        ec.future.cancel(false)
        timeoutSubjects -= key
      case None => // wasn't scheduled anymore
    }
  }
}

/**
  * an IndividualTimeoutMonitor that provides a set interface for TimeoutSubjects
  */
class IndividualTimeoutSet[V <: TimeoutSubject] extends IndividualTimeoutMonitor[V,V] {

  def contains (v: V): Boolean = timeoutSubjects.contains(v)
  def foreach[U] (f: V=>U): Unit = lock.synchronized{ timeoutSubjects.foreach(e=> f(e._2.subject)) }

  def += (v: V): Unit = lock.synchronized {
    addOrUpdate(v,v)
  }
}

/**
  * a monitor for a collection of objects that can expire
  * this monitor does not schedule individual expirations but periodically checks all added objects
  * it only does schedule if there are any, i.e. the check thread should not get invoked without candidates
  *
  * Each expiration is notified at most once between adding and removing it, i.e. if periodic/recurring notification
  * is required the objects have to be re-added upon their timeoutExpired notification
  *
  * Expiration deadline is not changed by this generic container (e.g. when adding subjects) - if this is
  * required it has to be set/updated by the subjects at the time when they are added
  */
trait BatchedTimeoutMonitor extends Runnable {
  def checkInterval: FiniteDuration  // how often do we check
  def size: Int // how many objects are currently added
  def isEmpty: Boolean
  def clear(): Unit

  protected def purgeExpired(): Unit // iterate over all currently added objects and purge/notify the expired ones. Called from a sync context

  protected val scheduler = Executors.newScheduledThreadPool(1)
  protected var sched: ScheduledFuture[_] = null
  protected val lock: AnyRef = new Object

  protected def schedule (r: Runnable): Unit = {
    sched = scheduler.schedule(r, checkInterval.toMillis, MILLISECONDS)
  }

  protected def cancel(): Unit = {
    if (sched != null) {
      sched.cancel(false)
      sched = null
    }
  }

  def run(): Unit = lock.synchronized {
    purgeExpired()
    if (isEmpty) cancel()
  }

  def terminate(): Unit = lock.synchronized {
    clear()
    scheduler.shutdown()
  }
}

/**
  * a generic BatchedTimeoutMonitor that keeps its elements in a map
  * we hide this map from the user since its access has to be thread-safe
  */
class BatchedTimeoutMap[K,V <:TimeoutSubject] (val checkInterval: FiniteDuration) extends BatchedTimeoutMonitor {
  private val timeoutSubjects: Map[K,V] = Map.empty

  override def size: Int = timeoutSubjects.size
  override def isEmpty: Boolean = timeoutSubjects.isEmpty

  override protected def purgeExpired(): Unit = {
    timeoutSubjects.foreach { e=>
      if (e._2.hasExpired) {
        timeoutSubjects -= e._1
        e._2.timeoutExpired()
      }
    }
  }

  //--- minimal map interface

  def contains (key: K): Boolean = lock.synchronized { timeoutSubjects.contains(key) }
  def apply (key: K): V = lock.synchronized { timeoutSubjects(key) }
  def get (key: K): Option[V] = lock.synchronized { timeoutSubjects.get(key) }
  def foreach[U] (f: ((K,V))=>U) = lock.synchronized { timeoutSubjects.foreach(f) }

  def += (e: (K,V)): Unit = lock.synchronized {
    if (!e._2.hasExpired) {
      timeoutSubjects += e
      if (timeoutSubjects.size == 1) schedule(this)

    } else { // already expired, don't add but notify
      e._2.timeoutExpired()
    }
  }

  def -= (key: K): Unit = lock.synchronized {
    timeoutSubjects -= key
    if (timeoutSubjects.isEmpty) cancel()
  }

  def -= (ts: V): Unit = lock.synchronized {
    timeoutSubjects.filterInPlace( (k,v)=> v != ts )
    if (timeoutSubjects.isEmpty) cancel()
  }

  override def clear(): Unit = lock.synchronized {
    timeoutSubjects.clear()
    cancel()
  }
}

/**
  * a generic BatchedTimeoutMonitor that keeps its elements in a set
  * we hide this set from the user since its access has to be thread-safe
  */
class BatchedTimeoutSet[V <:TimeoutSubject] (val checkInterval: FiniteDuration) extends BatchedTimeoutMonitor {
  private val timeoutSubjects: Set[V] = Set.empty

  override def size: Int = timeoutSubjects.size
  override def isEmpty: Boolean = timeoutSubjects.isEmpty

  override protected def purgeExpired(): Unit = {
    timeoutSubjects.foreach { e=>
      if (e.hasExpired) {
        timeoutSubjects -= e
        e.timeoutExpired()
      }
    }
  }

  //--- minimal set interface

  def contains (v: V): Boolean = lock.synchronized { timeoutSubjects.contains(v) }
  def foreach[U] (f: V=>U): Unit = lock.synchronized { timeoutSubjects.foreach(f) }

  def += (ts: V): Unit = lock.synchronized {
    if (!ts.hasExpired) {
      timeoutSubjects += ts
      if (timeoutSubjects.size == 1) schedule(this)
    } else {
      ts.timeoutExpired()
    }
  }

  def -= (ts: V): Unit = lock.synchronized {
    timeoutSubjects -= ts
    if (timeoutSubjects.isEmpty) cancel()
  }

  override def clear(): Unit = lock.synchronized {
    timeoutSubjects.clear()
    cancel()
  }
}

