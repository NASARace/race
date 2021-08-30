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

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Time.Milliseconds
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration.DurationInt

/**
  * reg tests for TimeoutQueues and TimeoutSubjects
  */
class TimeoutSpec extends AnyFlatSpec with RaceSpec {

  class SomeToS (val name: String, action: (SomeToS)=>Unit) extends TimeoutSubject {
    def timeout = Milliseconds(150)

    def timeoutExpired(): Unit = {
      println(s"    object '$name' timeout detected at: ${DateTime.now}'")
      action(this)
    }
  }

  "a BatchedTimeoutSet" should "properly notify TimeoutSubjects" in {
    println("\n##--- testing one time BatchedTimeoutSet notification")

    var expireFired = false
    val btq = new BatchedTimeoutSet[SomeToS](300.milliseconds)

    val o = new SomeToS("A", { _ => expireFired = true})

    println("    object queued: " + DateTime.now)
    btq += o
    Thread.sleep(500)
    println("    manual check at: " + DateTime.now)
    assert(o.hasExpired)
    assert(expireFired)
    expireFired = false
    println("    waiting for next cycle")
    Thread.sleep(500)
    assert(!expireFired)
    println("Ok.")

    btq.terminate()
  }

  "queuing an already expired TimeoutSubject" should "notify right away" in {
    println("\n##--- testing immediate notification of already expired object")

    val btq = new BatchedTimeoutSet[SomeToS](200.milliseconds)
    var n = 0

    println(s"    object created at: " + DateTime.now)
    val o = new SomeToS("A", { - => n += 1} )
    Thread.sleep(500)
    println(s"    expired object queued at: " + DateTime.now)
    btq += o
    Thread.sleep(500)
    assert(n == 1)
    println("Ok.")

    btq.terminate()
  }

  "a re-queued object" should "receive multiple notifications" in {
    println("\n##--- testing recurrent notification")

    val btq = new BatchedTimeoutSet[SomeToS](200.milliseconds)
    var n = 0

    val o = new SomeToS("A", { self=>
      if (n < 3) {
        n += 1
        println(s"    $n: re-queueing object at: ${DateTime.now}")

        self.resetExpiration()
        btq += self
      } else {
        println("    no re-queue on 3rd notification")
      }
    })


    println(s"    object queued at: " + DateTime.now)
    btq += o
    Thread.sleep(1000)
    assert( n == 3)
    println("Ok.")

    btq.terminate()
  }

  "a IndividualTimeoutMap" should "properly notify subjects" in {
    println("\n##--- testing individual timeouts")
    @volatile var pendingNotifications = 0

    val itm = new IndividualTimeoutMap[String,SomeToS]
    val o = new SomeToS("A", { self=>
      pendingNotifications -= 1
      if (pendingNotifications <0) fail("no pending nodification")
    })

    println("-- checking timeout notification")
    pendingNotifications = 1
    println(s"    object queued at: " + DateTime.now)
    itm += o.name -> o
    Thread.sleep(300)  // individual timeout is 150ms
    assert( pendingNotifications == 0) // o got notified
    assert( itm.isEmpty)

    println("-- checking explicit removal before timeout")
    o.resetExpiration()
    println(s"    object queued at: " + DateTime.now)
    itm += o.name -> o
    assert( itm.size == 1)
    println(s"    object removed at: " + DateTime.now)
    itm -= o.name // this should remove the scheduled notification
    Thread.sleep(500)
    assert( itm.isEmpty)

    println("Ok.")
  }
}
