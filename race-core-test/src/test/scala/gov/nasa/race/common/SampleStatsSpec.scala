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
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for various SampleStats implementations
  */
class SampleStatsSpec extends AnyFlatSpec with RaceSpec {

  val eps: Double = 1e-8

  "a DoubleSampleStats" should "reproduce known values" in {
    val samples = Array[Double](1,1,1,2,2,2,3,3,3,4,4,2,2)
    val stats = new DoubleStats
    println(s"""--- mean and variance of ${samples.mkString(", ")}""")
    println("     (compare online with after-the-fact)")

    samples.foreach(stats.addSample)

    val mean: Double = samples.foldLeft(0.0){ (sum,v) => sum + v } / samples.length
    val variance: Double = samples.foldLeft(0.0){ (sum,v) => sum + squared(v - mean) } / samples.length

    println(s"  mean:      ${stats.mean} / $mean : d=${mean - stats.mean}")
    println(s"  variance:  ${stats.variance} / $variance : d=${variance - stats.variance}")

    assert(expectWithin(stats.mean,mean,eps))
    assert(expectWithin(stats.variance,variance,eps))
  }
}
