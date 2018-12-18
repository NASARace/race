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

/**
  * a time based interpolant
  * we store time relative to the first time point, which limits the length of observations
  */
trait TInterpolant {
  val t0: Long
  val ts: Array[Int]

  val N = ts.length
  val N1 = N-1

  val tEnd = ts(N1)

  def startTime: Long = t0
  def endTime: Long = t0 + ts(N1)

  @inline protected def getTMin (t: Long): Int = if (t < t0) 0 else (t - t0).toInt
  @inline protected def getTMax (t: Long): Int = if (t > (t0 + ts(N1))) ts(N1) else (t - t0).toInt

  @inline protected final def getLeftIndex(t: Int): Int = {
    if (t < 0 || t > ts(N1)) return -1 // outside our interval
    var i = 1
    while (ts(i) <= t) i += 1
    i-1
  }

  @inline protected final def getLeftIndexReverse(t: Int): Int = {
    if (t < 0 || t > ts(N1)) return -1 // outside our interval
    var i = N1
    while (ts(i) > t) i -= 1
    i
  }

  protected final def findLeftIndex (t: Int): Int = {
    if (t < 0) {  // lower than start
      -1
    } else if (t > ts(N1)) {  // higher than end
      N1
    }
    else {
      var a = 0
      var b = N1
      while (b - a > 1){
        val c = (a + b)/2;
        val tc = ts(c);
        if (t == tc) return c
        else if (t > tc) a = c
        else b = c
      }
      a
    }
  }

  /**
    * find highest index with ts[i] <= t by means of bisection
    * assumes ts[] is ordered and strictly monotone
    */
  def findLeftIndexAbsolute (tAbs: Long): Int = findLeftIndex((tAbs - t0).toInt)

}

trait T1Interpolant extends TInterpolant {
  def eval (t: Long): Double
  def interpolate (tStart: Long, tEnd: Long, dt: Int): Array[Double]
  def interpolateHeadInto (result: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int
  def interpolateReverseTailInto (result: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int
  def evalFromTo (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Boolean): Unit
  def evalFromToReverse (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Boolean): Unit
}