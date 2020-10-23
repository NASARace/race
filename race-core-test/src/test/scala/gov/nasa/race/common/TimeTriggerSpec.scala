/*
 * Copyright (c) 2020, United States Government, as represented by the
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
import gov.nasa.race.uom.{DateTime, Time}
import org.scalatest.flatspec.AnyFlatSpec

class TimeTriggerSpec extends AnyFlatSpec with RaceSpec {

  "a SinceTrigger" should "properly fire" in {
    val tt = TimeTrigger("2s") // this should be a SinceTrigger

    println(s"-- check pass-fire-pass of $tt")
    tt.armCheck(DateTime.now)

    assert( tt.check(DateTime.now)) // first armed check always fires
    println("SinceTrigger init fire")
    Thread.sleep(100)
    assert( !tt.check(DateTime.now))
    Thread.sleep(2500)
    assert( tt.check(DateTime.now))
    println("SinceTrigger fired")
    Thread.sleep(100)
    assert( !tt.check(DateTime.now))
    Thread.sleep(2000)
    assert( tt.check(DateTime.now))
    println("SinceTrigger fired again")
  }

  "a TimeTrigger" should "properly fire" in {
    val t = DateTime.now + Time.Milliseconds(1000)
    val tt = TimeTrigger(t.format_Hms) // this should be a TimeOfDayTrigger

    println(s"-- check pass-fire-pass of $tt")
    tt.armCheck(DateTime.now)

    assert( !tt.check(DateTime.now))
    Thread.sleep(1500)
    assert( tt.check(DateTime.now))
    println("TimeTrigger fired")
    Thread.sleep(100)
    assert( !tt.check(DateTime.now))
  }

}
