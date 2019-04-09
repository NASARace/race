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
package gov.nasa.race.common

/**
  * mixin type for generic time series data
  * this one only keeps track of update time / intervals
  */
trait TimeSeriesUpdateStats {
  var tLast: Long = 0
  var sampleCount = 0

  var maxUpdateMillis: Int = 0
  var minUpdateMillis: Int = 0
  def averageUpdateFrequency: Int = if (sampleCount > 0) (dtTotal / sampleCount).toInt else 0

  private var dtTotal: Long = 0


  // can be overridden to use simulation time etc
  def sampleTime: Long = System.currentTimeMillis

  def addSample: Unit = {
    sampleCount += 1
    if (sampleCount > 1) {
      val dt = (sampleTime - tLast).toInt
      dtTotal += dt
      if (dt > maxUpdateMillis) maxUpdateMillis = dt
      if (minUpdateMillis == 0 || dt < minUpdateMillis) minUpdateMillis = dt
    }
  }
}

// we might add derived traits that keep track of values