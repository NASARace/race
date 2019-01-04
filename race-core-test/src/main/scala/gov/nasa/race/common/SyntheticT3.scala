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
package gov.nasa.race.common

import gov.nasa.race.common.TInterpolant.Data3

/**
  * helper class to generate T3 test data
  */
class SyntheticT3 (val ts: Array[Long], fx: (Long)=>Double, fy: (Long)=>Double, fz: (Long)=>Double) {
  val xs: Array[Double] = ts.map(fx)
  val ys: Array[Double] = ts.map(fy)
  val zs: Array[Double] = ts.map(fz)

  def interpolateFH: FHT3Interpolant = {
    def _getT(i: Int): Long = ts(i)
    def _getDataPoint(i: Int, d: Data3): Data3 = d.updated(ts(i),xs(i),ys(i),zs(i))

    new FHT3Interpolant(ts.length)(_getT)(_getDataPoint)
  }

  def interpolateLin: LinT3Interpolant = {
    def _getT(i: Int): Long = ts(i)
    def _getDataPoint(i: Int, d: Data3): Data3 = d.updated(ts(i),xs(i),ys(i),zs(i))

    new LinT3Interpolant(ts.length)(_getT)(_getDataPoint)
  }

}
