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

import java.lang.Math.{max, min}

import gov.nasa.race.common.T4

object USTrajectoryTrace {

  // geo center of contiguous US
  val USCenterLat = 39.833333
  val USCenterLon = -98.583333

  val D = 3 // we use 3rd order polynomials
}

/**
  * a memory optimized trajectory trace for time-limited flights within the continental US.
  *
  * Uses 16 bytes per track point but does not require en/decoding
  *
  * trajectories can not extend 900h duration, position is accurate within +- 1m (still better
  * than most GPS)
  */
class USTrajectoryTrace (val capacity: Int) extends TrajectoryTrace {
  import USTrajectoryTrace._

  var t0Millis: Long = -1 // start time of trajectory in epoch millis (set when entering first point)
  var tLast: Long = -1

  val ts: Array[Int] = new Array(capacity)
  val dlats: Array[Float] = new Array(capacity)
  val dlons: Array[Float] = new Array(capacity)
  val alts: Array[Float] = new Array(capacity)

  var tCalc: Long = -1 // time stamp of last coefficient calculation
  val w: Array[Float] = new Array(capacity) // barycentric weights for Floater Hormann interpolation

  // incremental update for all other track points
  protected def calcWeights: Unit = {
    val n1 = _size - 1
    val d = D
    var sign = if (D % 2 > 0) -1 else 1

    // recalc from tail to head
    var k = 0
    while (k <= n1) {
      var s: Double = 0
      val tk = ts(storeIdx(k))
      val iMin = max(k - d, 0)
      val iMax = min(k, n1 - d)
      var i = iMin
      while (i <= iMax) {
        var v: Double = 1
        val jMax = i + d
        var j = i
        while (j < k)     { v /= tk - ts(storeIdx(j)); j += 1 }
        j += 1
        while (j <= jMax) { v /= ts(storeIdx(j)) - tk; j += 1 }
        s += v
        i += 1
      }
      w(storeIdx(k)) = sign * s.toFloat
      sign = -sign
      k += 1
    }

    tCalc = tLast
  }

  override protected def setTrackPointData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double): Unit = {
    if (t0Millis < 0) {
      t0Millis = t
      ts(idx) = 0
    } else {
      ts(idx) = (t - t0Millis).toInt
    }
    tLast = t

    //calcWeights

    dlats(idx) = (lat - USCenterLat).toFloat
    dlons(idx) = (lon - USCenterLon).toFloat
    alts(idx) = alt.toFloat
  }

  override protected def processTrackPointData(i: Int, idx: Int, f: (Int, Long, Double, Double, Double) => Unit): Unit = {
    f(i, ts(idx) + t0Millis, dlats(idx) + USCenterLat, dlons(idx) + USCenterLon, alts(idx).toDouble)
  }

  type Result = T4[Long,Double,Double,Double]

  @inline protected final def _eval3 (t: Int, dist: Int, result: Result): Unit = {
    val n1 = _size-1
    val w = this.w
    val ts = this.ts

    var sDenom, sx, sy, sz: Double = 0.0
    var i = 0
    while (i <= n1){
      val si = storeIdx(i)
      val wd = (w(si) * dist) / (t - ts(si))
      sDenom += wd

      sx += wd * dlats(si)
      sy += wd * dlons(si)
      sz += wd * alts(si)

      i += 1
    }

    result._1 = t + t0Millis
    result._2 = (sx / sDenom) + USCenterLat
    result._2 = (sy / sDenom) + USCenterLon
    result._3 = sz / sDenom
  }

  // this returns a logical index 0.._size-1
  protected final def findLeftIndex (t: Int): Int = {
    val n1 = _size - 1

    if (t < ts(tail)) {  // lower than start
      -1
    } else if (t > ts(head)) {  // higher than end
      n1
    }
    else {
      var a = 0
      var b = n1
      while (b - a > 1){
        val c = (a + b)/2;
        val tc = ts(storeIdx(c));
        if (t == tc) return c
        else if (t > tc) a = c
        else b = c
      }
      a
    }
  }

  protected[this] class ForwardIterator[T] (tStart: Long, tEnd: Long, dt: Int)
                                           (exact: (Int,Int)=>T)
                                           (approx: (Int,Int)=>T) extends Iterator[T] {
    val n1 = _size - 1
    val tLeft: Int = (tStart - + t0Millis).toInt - ts(tail)
    val tRight: Int = (tEnd - t0Millis).toInt - ts(head)

    var i = findLeftIndex(tLeft)
    var tPrev = if (i < 0) Int.MinValue else ts(storeIdx(i))
    var tNext = if (i == n1) Int.MaxValue else ts(storeIdx(i+1))
    var t = tLeft

    override def hasNext: Boolean = t <= tEnd

    override def next(): T = {
      if (t > tRight) throw new NoSuchElementException(s"t = $t outside range [$tLeft..$tRight]")

      var iPrev = i
      while (t >= tNext) {
        iPrev = i
        i += 1
        tPrev = tNext
        tNext = if (i == n1) Int.MaxValue else ts(storeIdx(i))
      }

      val tt = t
      t += dt

      if (tt == tPrev) { // no need to compute
        exact(tt, storeIdx(iPrev))
      } else {
        approx(tt, Math.min(t - tPrev, tNext - tt))
      }
    }
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    if (tCalc < tLast) calcWeights // on demand
    val result = new Result(0,0.0,0.0, 0.0)
    def exact (t: Int, i: Int): Result = {
      result.updated(t,dlats(i) + USCenterLat, dlons(i) + USCenterLon, alts(i))
    }
    def approx (t: Int, dist: Int): Result = { _eval3(t,dist,result); result }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  protected[this] class ReverseIterator[T] (tEnd: Long, tStart: Long, dt: Int)
                                           (exact: (Int,Int)=>T)
                                           (approx: (Int,Int)=>T) extends Iterator[T] {
    val n1 = _size - 1
    val tLeft: Int = (tStart - + t0Millis).toInt - ts(tail)
    val tRight: Int = (tEnd - t0Millis).toInt - ts(head)

    var i = findLeftIndex(tRight)
    var tPrev = if (i < 0) Int.MinValue else ts(storeIdx(i))
    var tNext = if (i == n1) Int.MaxValue else ts(storeIdx(i+1))
    var t = tRight

    override def hasNext: Boolean = t >= tLeft

    override def next(): T = {
      if (t < tLeft) throw new NoSuchElementException(s"t = $t outside range [$tLeft..$tRight]")

      var iNext = i
      while (t <= tPrev && i > 0) {
        iNext = i
        i -= 1
        tNext = tPrev
        tPrev = if (i == 0) Int.MinValue else ts(storeIdx(i))
      }

      val tt = t
      t -= dt

      if (tt == tNext) { // no need to compute
        exact(tt, storeIdx(iNext))
      } else {
        approx(tt, Math.min(tt - tPrev, tNext - tt))
      }
    }
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    if (tCalc < tLast) calcWeights // on demand
    val result = new Result(0,0.0,0.0, 0.0)
    def exact (t: Int, i: Int): Result = {
      result.updated(t,dlats(i) + USCenterLat, dlons(i) + USCenterLon, alts(i))
    }
    def approx (t: Int, dist: Int): Result = { _eval3(t,dist,result); result }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Result] = {
    val tEnd = ts(head) + t0Millis
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Result] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}
