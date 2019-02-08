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

import gov.nasa.race.common.Nat.N3

/**
  * helper class to generate T3 test data
  */
class SyntheticT3 (val ts: Array[Long],
                   fx: (Long)=>Double,
                   fy: (Long)=>Double,
                   fz: (Long)=>Double) extends TDataSource3 {
  val xs: Array[Double] = ts.map(fx)
  val ys: Array[Double] = ts.map(fy)
  val zs: Array[Double] = ts.map(fz)

  override def size: Int = ts.length
  override def getT(i: Int): Long = ts(i)
  override def getDataPoint(i: Int, p: TDataPoint3): TDataPoint3 = p.set(ts(i),xs(i),ys(i),zs(i))
  override def newDataPoint: TDataPoint3 = new TDataPoint3(0,0,0,0)


  def interpolateLin: LinTInterpolant[N3,TDataPoint3] = new LinTInterpolant[N3,TDataPoint3](this)
  def interpolateFH: FHTInterpolant[N3,TDataPoint3] = new FHTInterpolant[N3,TDataPoint3](this)
}
