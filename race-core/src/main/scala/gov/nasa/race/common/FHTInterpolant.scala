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

import java.lang.Math.{max, min}

import gov.nasa.race.common.Nat.{N1,N2,N3}


/**
  * Interpolant that uses barycentric rational interpolation according to
  * M. Floater, K. Hormann: "Barycentric rational interpolation with no poles and high rates of approximation"
  * https://www.inf.usi.ch/hormann/papers/Floater.2007.BRI.pdf
  *
  * Note that we do not imply a specific time or value storage but require a TDataSource that maps logical indices
  * [0..n-1] to observation DataPoints
  */
class FHTInterpolant[N<:Nat,T<:TDataPoint[N]](override val src: TDataSource[N,T], d: Int=3)
                                                                 extends TInterpolant[N,T](src) {
  private val w: Array[Double] = new Array(src.size) // barycentric weights
  val res: T = src.newDataPoint // result value cache
  val acc: T = src.newDataPoint // accumulator cache

  calcWeights

  private def calcWeights: Unit = {
    var sign: Int = if (d % 2 > 0) -1 else 1

    var k = 0
    while (k <= n1) {
      var s: Double = 0
      val tk = src.getT(k)
      val iMin = max(k - d, 0)
      val iMax = min(k, n1 - d)
      var i = iMin
      while (i <= iMax) {
        var v: Double = 1
        val jMax = i + d
        var j = i
        while (j < k)     { v /= tk - src.getT(j); j += 1 }
        j += 1
        while (j <= jMax) { v /= src.getT(j) - tk; j += 1 }
        s += v
        i += 1
      }
      w(k) = sign * s
      sign = -sign
      k += 1
    }
  }

  private def interpolate(t: Long, dist: Long): T = {
    res.clear
    var q: Double = 0
    var i = 0
    while (i <= n1){
      src.getDataPoint(i,acc)
      val wd = (w(i) * dist) / (t - acc.getTime)
      acc *= wd
      res += acc
      q += wd
      i += 1
    }

    res /= q
    res.setTime(t)
    res
  }

  //--- the public methods

  /**
    * a single time point interpolation
    */
  def eval (t: Long): T = {
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      interpolate(t, -t)
    } else if (j == n1) { // after last observation
      interpolate(t, t - src.getT(n1))
    } else {
      interpolate(t, min(t - src.getT(j), src.getT(j+1) - t))
    }
  }

  def iterator (tStart: Long, tEnd: Long, dt: Long): Iterator[T] = {
    def exact (t: Long, i: Int): T = { src.getDataPoint(i,res); res }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): T = interpolate(t,min(t-tPrev, tNext-t))
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Long): Iterator[T] = {
    def exact (t: Long, i: Int): T = { src.getDataPoint(i,res); res }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): T = interpolate(t,min(t-tPrev, tNext-t))
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }
}

//--- convenience types
class FHTInterpolant1(ts: Array[Long], v0:Array[Double])
                                       extends FHTInterpolant[N1,TDataPoint1](new ArrayTDataSource1(ts,v0))
class FHTInterpolant2(ts: Array[Long], v0:Array[Double], v1:Array[Double])
                                       extends FHTInterpolant[N2,TDataPoint2](new ArrayTDataSource2(ts,v0,v1))
class FHTInterpolant3(ts: Array[Long], v0:Array[Double], v1:Array[Double], v2:Array[Double])
                                       extends FHTInterpolant[N3,TDataPoint3](new ArrayTDataSource3(ts,v0,v1,v2))
