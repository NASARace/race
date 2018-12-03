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
package gov.nasa.race.track

import gov.nasa.race.test.RaceSpec
import org.scalatest.FlatSpec

/**
  * reg test for LossyTrajectory
  */
class CompressedTrackPathSpec extends FlatSpec with RaceSpec {

  "a init sized CompressedTrackPath" should "contain approximate values of entries in right order" in {
    val data = Array(
      (1, 37.62000,-122.38000, 3000.0),
      (2, 37.62001,-122.38001, 3001.0),
      (3, 37.62002,-122.38002, 3002.0)
    )

    val t = new CompressedTrackPath
    assert( t.isEmpty)
    for (d <- data)  t.addPre(d._1, d._2, d._3, d._4)

    assert( t.size == data.length)
    println("--- compact trajectory in order of entry:")
    t.foreachPre { (i, t, lat, lon, alt) =>
      println(f"$i: $t = ($lat%10.5f, $lon%10.5f, $alt%5.0f)")
      assert( t == data(i)._1)
      lat shouldBe( data(i)._2 +- 0.00001)
      lon shouldBe( data(i)._3 +- 0.00001)
      alt shouldBe( data(i)._4 +- 0.5)
    }

    println("--- compact trajectory in reverse order of entry:")
    t.foreachPreReverse { (i, t, lat, lon, alt) =>
      println(f"$i: $t = ($lat%10.5f, $lon%10.5f, $alt%5.0f)")
      assert( t == data(i)._1)
      lat shouldBe( data(i)._2 +- 0.00001)
      lon shouldBe( data(i)._3 +- 0.00001)
      alt shouldBe( data(i)._4 +- 0.5)
    }
  }

  "a CompressedTrackPath" should "be resized automatically" in {

    val data = Array(
      (0, 37.62000,-122.38000, 3000.0),
      (1, 37.62001,-122.38001, 3001.0),
      (2, 37.62002,-122.38002, 3002.0),
      (3, 37.62003,-122.38003, 3003.0),
      (4, 37.62004,-122.38004, 3004.0),
      (5, 37.62005,-122.38005, 3005.0),
      (6, 37.62006,-122.38006, 3006.0),
      (7, 37.62007,-122.38007, 3007.0),
      (8, 37.62008,-122.38008, 3008.0),
    )

    val t = new CompressedTrackPath(4)
    assert( t.isEmpty)
    for (d <- data)  t.addPre(d._1, d._2, d._3, d._4)
    assert( t.size == data.length)

    println("--- grown compressed trajectory in order of entry:")
    t.foreachPre { (i, t, lat, lon, alt) =>
      println(f"$i: $t = ($lat%10.5f, $lon%10.5f, $alt%5.0f)")
      assert( t == data(i)._1)
      lat shouldBe( data(i)._2 +- 0.00001)
      lon shouldBe( data(i)._3 +- 0.00001)
      alt shouldBe( data(i)._4 +- 0.5)
    }
  }

  "a time filtered CompressedTrackPath" should "only store entries with minimum time spacing" in {
    val data = Array(
      (100000, 37.62000,-122.38000, 3000.0),
      (100010, 37.62001,-122.38001, 3001.0),
      (100020, 37.62002,-122.38002, 3002.0),
      (100030, 37.62003,-122.38003, 3003.0),
      (100040, 37.62004,-122.38004, 3004.0),
      (100050, 37.62005,-122.38005, 3005.0),
      (100060, 37.62006,-122.38006, 3006.0),
      (100070, 37.62007,-122.38007, 3007.0),
      (100080, 37.62008,-122.38008, 3008.0),
      (100090, 37.62009,-122.38009, 3009.0),
    )

    val traj = new TimedTrajectoryFilter(new CompressedTrackPath(4), 20)
    assert( traj.isEmpty)
    for (d <- data)  traj.addPre(d._1, d._2, d._3, d._4)
    assert( traj.size == data.length/2)

    println("--- 20ms time filtered track path in order of entry")
    val (t,lat,lon,alt) = traj.getPositionsPre

    var i = 0
    var j = 0
    while (i < traj.size) {
      println(f"$i: ${t(i)} = (${lat(i)}%10.5f, ${lon(i)}%10.5f, ${alt(i)}%5.0f)")
      assert( t(i) == data(j)._1)
      lat(i) shouldBe( data(j)._2 +- 0.00001)
      lon(i) shouldBe( data(j)._3 +- 0.00001)
      alt(i) shouldBe( data(j)._4 +- 0.5)
      i += 1
      j += 2
    }
  }
}
