/*
 * Copyright (c) 2019, United States Government, as represented by the
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

trait SampleStats[T] {
  def numberOfSamples: Int
  def mean: T
  def variance: Double
}

/**
  * online statistics sampler
  * using Welford's algorithm to incrementally compute sample mean and variance
  */
class OnlineSampleStats extends SampleStats[Double] {

  protected var k = 0
  protected var m: Double = 0
  protected var s: Double = 0

  def addSample (x: Double): Unit = {
    k += 1
    val mNext = m + (x - m)/k
    s += (x - m) * (x - mNext)
    m = mNext
  }

  @inline def += (x: Double): Unit = addSample(x)

  def numberOfSamples: Int = k

  def mean: Double = m

  def variance: Double = s / (k-1)
}
