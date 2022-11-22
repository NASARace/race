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

package gov.nasa.race.util

import gov.nasa.race._

import scala.annotation.tailrec
import scala.concurrent.duration._

/**
 * common threading utils for RACE (signals)
 */
object ThreadUtils {

  // not worth the java.util.concurrent overhead if all we need is a lock

  class Signal {
    def wait(timeout: Duration): Unit = {
      this.synchronized {
        if (timeout.isFinite) {
          super.wait(timeout.toMillis)
        } else {
          super.wait()
        }
      }
    }

    def signal() = {
      this.synchronized {
        notifyAll
      }
    }

    def signalOnceDone(block: => Any) = {
      this.synchronized {
        block
        notifyAll()
      }
    }

    def executeProtected(block: => Any) = {
      this.synchronized {
        block
      }
    }
  }

  def yieldExecution(): Unit = Thread.`yield`()

  def sleep(duration: FiniteDuration) = Thread.sleep(duration.toMillis)

  def sleepInterruptible (duration: FiniteDuration): Unit = {
    try {
      Thread.sleep(duration.toMillis)
    } catch {
      case _: InterruptedException => // do nothing
    }
  }

  def waitInterruptibleUpTo[A] (dur: FiniteDuration, fallback: =>A)(waitOp: FiniteDuration=>A): A = {
    var d = dur

    while (d > Duration.Zero){
      val t0 = System.currentTimeMillis
      try {
        return waitOp(d) // this is a blocking call
      } catch {
        case _:InterruptedException =>
          val t1 = System.currentTimeMillis
          d = d.minus((t1 - t0).milliseconds)
        // everything else is escalated
      }
    }
    fallback
  }

  def pollUpTo(maxMillis: Int, pollMillis: Int)(poll: => Boolean): Boolean = {
    @tailrec def _pollNext(remaining: Int): Boolean = {
      if (remaining < 0) false
      else {
        Thread.sleep(pollMillis)
        if (poll) true else _pollNext(remaining - pollMillis)
      }
    }

    _pollNext(maxMillis)
  }

  def installShutdownHook (f: =>Unit): Unit = {
    val thread = new Thread {
      override def run: Unit = f
    }
    Runtime.getRuntime.addShutdownHook(thread)
  }

  def asRunnable(f: =>Unit) = new Runnable(){
    override def run(): Unit = f
  }

  def daemon (f: =>Unit): Thread = yieldInitialized(new Thread(asRunnable(f))){_.setDaemon(true)}

  def startDaemon (f: => Unit): Thread = {
    val t = new Thread(asRunnable(f))
    t.setDaemon(true)
    t.start
    t
  }

  def execAsync (f: => Unit): Unit = {
    new Thread(asRunnable(f)).start
  }

  /**
    * a daemon thread that loops until explicitly terminated, executing an action as long as a
    * dynamically evaluated condition is met, and then waiting (with configurable timeout)
    * until it is explicitly woken up again
    *
    * note that the finite timeout duration allows us to ignore missed signals (otherwise the
    * wakeup caller has to be synchronized on what changes the condition)
    */
  //
  class MonitorThread(waitTimeout: Duration = 10.seconds, // timeout to wait for wakeUp if cond got false
                      cycleDelay: FiniteDuration = 2.seconds, // delay between whileTrueAction and next condition eval
                      maxExceptions: Int = 5 // max number of consecutive exceptions until we terminate)
                      )(condition: => Boolean)(whileTrueAction: => Any) extends Thread {
    private val _signal = new Signal
    private var _done = false

    setDaemon(true)

    override def run: Unit = {
      var xToBreak = maxExceptions // number of consecutive exceptions until we give up
      while (!_done) {
        try {
          while (condition) {
            whileTrueAction
            sleep(cycleDelay)
          }
          xToBreak = maxExceptions // if we successfully passed through whileTrue we reset
          _signal.wait(waitTimeout)
        } catch {
          case x: InterruptedException => // go on
          case x: Throwable =>
            xToBreak -= 1
            if (xToBreak == 0) throw x
        }
      }
    }

    //--- the public API
    def wakeUp() = _signal.signal()
    def terminate() = {
      _done = true
      this.interrupt()
    }
  }

  /**
   * last ditch effort to stop a thread.
   * Use with care - see JDK javadoc why this is not reliable.
   * Mostly here so that we only get one deprecated warning.
   */
  def stop(thread: Thread) = {
    if (thread.isAlive) {
      // unbelievable what we have to do to avoid deprecated warnings for thread.stop()
      // WE KNOW IT IS NOT RELIABLE
      try {
        val stopMth = thread.getClass.getMethod("stop")
        stopMth.invoke(thread)
      } catch {
        case _: Throwable => // ignore, nothing else we can do
      }
    }
  }
}
