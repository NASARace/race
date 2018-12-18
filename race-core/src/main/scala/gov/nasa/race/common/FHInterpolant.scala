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

import java.lang.Math._

abstract class FHInterpolant (val t0: Long, val ts: Array[Int], val d: Int) extends TInterpolant {
  val w: Array[Double] = new Array(N)  // barycentric weights
  calcWeights


  protected def calcWeights: Unit = {
    val n1 = this.N1
    val d  = this.d

    var sign: Int = if (d % 2 > 0) -1 else 1

    var k = 0
    while (k <= n1) {
      var s: Double = 0
      val tk = ts(k)
      val iMin = max(k - d, 0)
      val iMax = min(k, n1 - d)
      var i = iMin
      while (i <= iMax) {
        var v: Double = 1
        val jMax = i + d
        var j = i
        while (j < k)     { v /= tk - ts(j); j += 1 }
        j += 1
        while (j <= jMax) { v /= ts(j) - tk; j += 1 }
        s += v
        i += 1
      }
      w(k) = sign * s
      sign = -sign
      k += 1
    }
  }
}

/**
  * Interpolant that uses barycentric rational interpolation according to
  * M. Floater, K. Hormann: "Barycentric rational interpolation with no poles
  * and high rates of approximation", https://www.inf.usi.ch/hormann/papers/Floater.2007.BRI.pdf
  */
class FHT1Interpolant (_t0: Long, _ts: Array[Int], val vs: Array[Double], _d: Int=3)
                    extends FHInterpolant(_t0, _ts, min(_d,_ts.length)) with T1Interpolant {


  /**
    * a single time point interpolation
    */
  def eval (tAbs: Long): Double = {
    val t: Int = (tAbs - t0).toInt
    val j = findLeftIndex(t)

    if (t == ts(j)) vs(j) else _eval(t, _getDist(t, j))
  }

  @inline protected final def _eval (t: Int, dist: Int): Double = {
    val n1 = N1
    val w = this.w
    val ts = this.ts
    val vs = this.vs

    var sumNoms: Double = 0
    var sumDenoms: Double = 0;
    var i = 0
    while (i <= n1){
      val wd = (w(i) * dist) / (t - ts(i))
      sumNoms += wd * vs(i)
      sumDenoms += wd
      i += 1
    }
    sumNoms / sumDenoms
  }

  @inline protected final def _getDist(t: Int, i: Int): Int = {
    if (i < 0) {
      ts(0) - t
    } else if (i >= N1) {
      t - tEnd
    } else {
      val dl = t - ts(i)
      val dr = ts(i+1) - t
      if (dl > dr) dr else dl
    }
  }

  //--- result array interpolation

  def interpolate (tStart: Long, tEnd: Long, dt: Int): Array[Double] = {
    val len = 1 + (tEnd - tStart).toInt / dt
    val result = new Array[Double](len)
    var i = 0
    evalFromTo(tStart, tEnd, dt) { (t,v) =>
      result(i) = v
      i += 1
      true
    }
    result
  }

  def interpolateHeadInto (result: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val l = 1 + (tEnd - tStart).toInt / dt
    val te = if (l > result.length) tStart + (result.length-1) * dt else tEnd
    var i = 0
    evalFromTo(tStart, te, dt) { (t,v) =>
      result(i) = v
      i += 1
      true
    }
    i
  }
  def interpolateReverseTailInto (result: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val l = 1 + (tEnd - tStart).toInt / dt
    val ts = if (l > result.length) tEnd - (result.length-1) * dt else tStart
    var i = 0
    evalFromToReverse(ts, tEnd, dt) { (t,v) =>
      result(i) = v
      i += 1
      true
    }
    i
  }

  //--- forward iteration

  @inline protected final def _getV (t: Int, tPrev: Int, iPrev: Int, tNext: Int): Double = {
    if (t == tPrev) { // no need to compute
      vs(iPrev)
    } else {
      _eval(t, min(t - tPrev, tNext - t))
    }
  }

  /**
    * iterator-less forward increments
    */
  def evalFromTo (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Boolean): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    var i = findLeftIndex(tLeft)
    var iPrev = i
    var tPrev = if (i < 0) Int.MinValue else ts(i)
    var tNext = if (i == N1) Int.MaxValue else ts(i+1)
    var t = tLeft

    while (t <= tRight) {
      while (t >= tNext && i < N1) {
        iPrev = i
        i += 1
        tPrev = tNext
        tNext = if (i == N1) Int.MaxValue else ts(i)
      }

      if (!f(t+t0, _getV(t,tPrev,iPrev,tNext))) return
      
      t += dt
    }
  }

  class ForwardIterator (result: LD, tStart: Long, tEnd: Long, dt: Int) extends Iterator[LD] {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    var i = findLeftIndex(tLeft)
    var iPrev = i
    var tPrev = if (i < 0) Int.MinValue else ts(i)
    var tNext = if (i == N1) Int.MaxValue else ts(i+1)
    var t = tLeft

    override def hasNext: Boolean = t <= tRight

    override def next: LD = {
      if (t > tRight) throw new NoSuchElementException(s"t = $t outside range [$tLeft..$tRight]")

      while (t >= tNext) {
        iPrev = i
        i += 1
        tPrev = tNext
        tNext = if (i == N1) Int.MaxValue else ts(i)
      }

      result.set( t+t0, _getV(t,tPrev,iPrev,tNext))
      t += dt
      result
    }
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int, ld: LD = new LD): Iterator[LD] = {
    new ForwardIterator(ld, tStart, tEnd, dt)
  }

  //--- backwards iteration

  @inline protected final def _getVReverse (t: Int, tPrev: Int, tNext: Int, iNext: Int): Double = {
    if (t == tNext) {
      vs(iNext)
    } else {
      val dl = t - tPrev
      val dr = tNext - t
      val d = if (dl > dr) dr else dl
      _eval(t, d)
    }
  }

  def evalFromToReverse (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Boolean): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    var i = findLeftIndex(tRight)
    var iNext = i
    var tPrev = if (i < 0) Int.MinValue else ts(i)
    var tNext = if (i == N1) Int.MaxValue else ts(i+1)
    var t = tRight

    while (t >= tLeft) {
      while (t <= tPrev) {
        iNext = i
        i -= 1
        tNext = tPrev
        tPrev = if (i == 0) Int.MinValue else ts(i)
      }
      if (!f( t+t0, _getVReverse(t, tPrev, tNext, iNext))) return
      t -= dt
    }
  }

  class ReverseIterator (result: LD, tStart: Long, tEnd: Long, dt: Int) extends Iterator[LD] {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    var i = findLeftIndex(tRight)
    var iNext = i
    var tPrev = if (i < 0) Int.MinValue else ts(i)
    var tNext = if (i == N1) Int.MaxValue else ts(i+1)
    var t = tRight

    override def hasNext: Boolean = t >= tLeft

    override def next: LD = {
      if (t < tLeft) throw new NoSuchElementException(s"t = $t outside range [$tLeft..$tRight]")
      while (t <= tPrev) {
        iNext = i
        i -= 1
        tNext = tPrev
        tPrev = if (i == 0) Int.MinValue else ts(i)
      }
      result.set(t + t0, _getVReverse(t, tPrev, tNext, iNext))
      t -= dt
      result
    }
  }

  def reverseIterator (tStart: Long, tEnd: Long, dt: Int, ld: LD = new LD): Iterator[LD] = {
    new ReverseIterator(ld, tStart, tEnd, dt)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int, ld: LD = new LD): Iterator[LD] = {
    val tEnd = ts(N1) + t0
    val tStart = tEnd - dur
    new ReverseIterator(ld, tStart, tEnd, dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int, ld: LD = new LD): Iterator[LD] = {
    val tStart = tEnd - dur
    new ReverseIterator(ld, tStart, tEnd, dt)
  }
}
