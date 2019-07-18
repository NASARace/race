/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.trajectory

import gov.nasa.race.common.FHTInterpolant
import gov.nasa.race.common.Nat.N3
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime.ofEpochMillis
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Time._
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for LossLessTrace
  */
class AccurateTraceSpec extends AnyFlatSpec with RaceSpec {

  "an empty trace" should "not produce any values" in {
    val trace = new AccurateTrace(4)
    trace.size shouldBe(0)

    trace.foreach { p =>
      fail("entry in an empty trace")
    }
  }

  "a non-saturated trace" should "contain exact values of entries in right order" in {
    val data = Array(
      (0, 10.0, 20.0, 100.0),
      (1, 10.1, 20.1, 100.1),
      (2, 10.2, 20.2, 100.2)
    )

    val traj = new AccurateTrace(4)
    for (d <- data) traj.append(ofEpochMillis(d._1), Degrees(d._2), Degrees(d._3), Meters(d._4))

    assert(traj.size == data.length)

    println("--- un-saturated trace in order of entry:")
    var i = 0
    traj.foreach(traj.newDataPoint) { p =>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")
      assert(p.millis == data(i)._1)
      p._0 shouldBe (data(i)._2)
      p._1 shouldBe (data(i)._3)
      p._2 shouldBe (data(i)._4)
      i += 1
    }

    println("--- un-saturated trace in reverse order of entry:")
    i = traj.size-1
    traj.foreachReverse(traj.newDataPoint){ p =>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")
      assert(p.millis == data(i)._1)
      p._0 shouldBe (data(i)._2)
      p._1 shouldBe (data(i)._3)
      p._2 shouldBe (data(i)._4)
      i -= 1
    }
  }

  "a saturated trace" should "contain exact values of entries in right order" in {
    val data = Array(
      (0, 10.0, 20.0, 100.0),
      (1, 10.1, 20.1, 100.1),
      (2, 10.2, 20.2, 100.2),
      (3, 10.3, 20.3, 100.3),
      (4, 10.4, 20.4, 100.4),
      (5, 10.5, 20.5, 100.5)
    )

    val traj = new AccurateTrace(4)
    for (d <- data) traj.append(ofEpochMillis(d._1), Degrees(d._2), Degrees(d._3), Meters(d._4))

    assert(traj.size == traj.capacity)

    println("--- saturated trace in order of entry:")
    var i = 0
    traj.foreach(traj.newDataPoint) { p =>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")
      val j = data.length - traj.capacity + i

      assert(p.millis == data(j)._1)
      p._0 shouldBe (data(j)._2)
      p._1 shouldBe (data(j)._3)
      p._2 shouldBe (data(j)._4)
      i += 1
    }


    println("--- saturated trace in reverse order of entry:")
    i = traj.size-1
    traj.foreachReverse(traj.newDataPoint){ p =>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")
      val j = data.length - (traj.capacity - i)

      assert(p.millis == data(j)._1)
      p._0 shouldBe (data(j)._2)
      p._1 shouldBe (data(j)._3)
      p._2 shouldBe (data(j)._4)
      i -= 1
    }
  }

  "a partial AccurateTrace snapshot" should "be immutable and cover the requested duration" in {
    val data = Array(
      (0, 10.0, 20.0, 100.0),
      (1, 10.1, 20.1, 100.1),
      (2, 10.2, 20.2, 100.2),
      (3, 10.3, 20.3, 100.3),
      (4, 10.4, 20.4, 100.4),
      (5, 10.5, 20.5, 100.5)
    )

    val cap = 5
    println(s"--- AccurateTrace($cap) traceSnapshot(Milliseconds(3)):")

    var i = 0
    println(s"data:")
    val traj = new AccurateTrace(cap)
    for (d <- data) {
      println(s"$i: $d")
      traj.append(ofEpochMillis(d._1), Degrees(d._2), Degrees(d._3), Meters(d._4))
      i += 1
    }

    println(s"source trace ($cap):")
    i = 0
    traj.foreach(traj.newDataPoint) { p=>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")
      i += 1
    }

    val dur = Milliseconds(3)
    val snap = traj.traceSnapshot(dur)
    i = 0
    var j = data.length - snap.size
    println(s"snapshot($dur) is a ${snap.getClass}: ")
    snap.foreach(traj.newDataPoint) { p=>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.0f)")

      assert(p.millis == data(j)._1)
      p._0 shouldBe (data(j)._2)
      p._1 shouldBe (data(j)._3)
      p._2 shouldBe (data(j)._4)

      j += 1
      i += 1
    }
    println(s"snapshot duration = ${snap.getDuration}")
    assert(snap.getDuration <= dur)
  }

  "a interpolated trace" should "match linear data model" in {
    val data = Array(
      (0, 10.0, 20.0, 100.0),
      (1, 10.1, 20.1, 100.1),
      //(2, 10.2, 20.2, 100.2),
      //(3, 10.3, 20.3, 100.3),
      (4, 10.4, 20.4, 100.4),
      (5, 10.5, 20.5, 100.5),
      //(6, 10.6, 20.6, 100.6),
      (7, 10.7, 20.7, 100.7),
      //(8, 10.8, 20.8, 100.8),
      (9, 10.9, 20.9, 100.9)
    )

    val cap = 5
    println(s"--- interpolated trace($cap)")

    var i = 0
    println(s"data:")
    val traj = new AccurateTrace(cap)
    for (d <- data) {
      println(s"$i: $d")
      traj.append(ofEpochMillis(d._1), Degrees(d._2), Degrees(d._3), Meters(d._4))
      i += 1
    }

    println(s"source trace ($cap):")
    i = 0
    traj.foreach(traj.newDataPoint) { p=>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.1f)")
      i += 1
    }

    val dStart = ofEpochMillis(3)
    val dEnd = ofEpochMillis(8)
    val interval = Milliseconds(1)
    println(s"interpolated trajectory (start=${dStart.toEpochMillis},end=${dEnd.toEpochMillis},interval=${interval.toMillis}) :")

    val trajInt = traj.interpolate(dStart,dEnd,interval)(new FHTInterpolant[N3,TDP3](_))

    i = 0
    trajInt.foreach(traj.newDataPoint) { p=>
      println(f"$i: ${p.millis} = (${p._0}%10.5f, ${p._1}%10.5f, ${p._2}%5.1f)")

      assert( p.millis == dStart.millis + i)
      p._0  shouldBe( (10.0 + p.millis/10.0) +- 0.00001)
      p._1  shouldBe( (20.0 + p.millis/10.0) +- 0.00001)
      p._2  shouldBe( (100.0 + p.millis/10.0) +- 0.00001)

      i += 1
    }
  }
}
