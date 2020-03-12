/*
 * Copyright (c) 2017, United States Government, as represented by the
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
  * unit test for BucketCounter
  */
class BucketCounterSpec extends AnyFlatSpec with RaceSpec {

  val eps = 0.000001

  "static input set" should "produce right mean and variance" in {
    val population: Array[Int] = Array(1,2,3,3,2,3,4)
    val sampleSet = new BucketCounter(0,5,5,useOutliers=true)

    var n: Int = 0
    var sum: Double = 0

    population.foreach { v =>
      n += 1
      sum += v
      val mean: Double = sum / n

      var xd: Double = 0
      for (i <- 0 until n) xd += squared(population(i) - mean)
      val variance = if (n>1) xd / n else 0.0

      sampleSet.addSample(v)
      print(f"v=$v, n=${sampleSet.numberOfSamples}, m=${sampleSet.mean}%5.3f, s2=${sampleSet.variance}%5.3f")
      println(s"   buckets= ${sampleSet.showBuckets}")

      sampleSet.mean shouldBe mean+-eps
      sampleSet.variance shouldBe variance+-eps
    }
  }
}
