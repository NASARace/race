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

import java.lang.Math.{min,max}

import scala.annotation.tailrec

/**
  * Interpolant that uses barycentric rational interpolation according to
  * M. Floater, K. Hormann: "Barycentric rational interpolation with no poles and high rates of approximation"
  * https://www.inf.usi.ch/hormann/papers/Floater.2007.BRI.pdf
  */
abstract class FHInterpolant (val t0: Long, val ts: Array[Int], val d: Int) extends TInterpolant {
  protected[this] class BreakException extends Exception
  protected[this] val breakException = new BreakException

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

  @inline protected final def _eval (t: Int, dist: Int, vs: Array[Double]): Double = {
    val n1 = N1
    val w = this.w
    val ts = this.ts

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

  /**
    * use from within loop callbacks to break iteration
    */
  def break: Unit = throw breakException

  @tailrec protected final def _evalForward (_i: Int, t: Int, _tPrev: Int, _tNext: Int, tEnd: Int, dt: Int)
                                            (exact: (Int,Int)=>Unit)
                                            (approx: (Int,Int)=>Unit): Unit = {
    if (t <= tEnd) {
      var i = _i
      var tPrev = _tPrev
      var tNext = _tNext
      var iPrev = i

      while (t >= tNext && i < N1) {
        iPrev = i
        i += 1
        tPrev = tNext
        tNext = if (i == N1) Int.MaxValue else ts(i)
      }

      if (t == tPrev) { // no need to compute
        exact(t, iPrev)
      } else {
        approx(t, min(t - tPrev, tNext - t))
      }

      _evalForward(i, t + dt, tPrev, tNext, tEnd, dt)(exact)(approx)
    }
  }

  /**
    * iterator version - can be used to iterate over several functions at the same time
    */
  protected[this] class ForwardIterator[T] (tStart: Long, tEnd: Long, dt: Int)
                           (exact: (Int,Int)=>T)
                           (approx: (Int,Int)=>T) extends Iterator[T] {
    var tLeft: Int = (tStart - t0).toInt
    var tRight: Int = (tEnd - t0).toInt

    var i = findLeftIndex(tLeft)
    var tPrev = if (i < 0) Int.MinValue else ts(i)
    var tNext = if (i == N1) Int.MaxValue else ts(i+1)
    var t = tLeft

    override def hasNext: Boolean = t <= tEnd

    override def next(): T = {
      if (t > tRight) throw new NoSuchElementException(s"t = $t outside range [$tLeft..$tRight]")

      var iPrev = i
      while (t >= tNext) {
        iPrev = i
        i += 1
        tPrev = tNext
        tNext = if (i == N1) Int.MaxValue else ts(i)
      }

      val tt = t
      t += dt

      if (tt == tPrev) { // no need to compute
        exact(tt, iPrev)
      } else {
        approx(tt, Math.min(t - tPrev, tNext - tt))
      }
    }
  }

  @tailrec protected final def _evalReverse (_i: Int, t: Int, _tPrev: Int, _tNext: Int, tLeft: Int, dt: Int)
                                            (exact: (Int,Int)=>Unit)
                                            (approx: (Int,Int)=>Unit): Unit = {
    if (t >= tLeft) {
      var i = _i
      var tPrev = _tPrev
      var tNext = _tNext
      var iNext = i

      while (t <= tPrev && i > 0) {
        iNext = i
        i -= 1
        tNext = tPrev
        tPrev = if (i == 0) Int.MinValue else ts(i)
      }

      if (t == tNext) {
        exact(t, iNext)
      } else {
        approx(t, min(t - tPrev, tNext - t))
      }

      _evalReverse(i, t - dt, tPrev, tNext, tLeft, dt)(exact)(approx)
    }
  }

  protected[this] class ReverseIterator[T] (tEnd: Long, tStart: Long, dt: Int)
                                             (exact: (Int,Int)=>T)
                                             (approx: (Int,Int)=>T) extends Iterator[T] {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    var i = findLeftIndex(tRight)
    var tPrev = if (i < 0) Int.MinValue else ts(i)
    var tNext = if (i == N1) Int.MaxValue else ts(i + 1)
    var t = tRight

    override def hasNext: Boolean = t >= tLeft

    override def next(): T = {
      if (t < tLeft) throw new NoSuchElementException(s"t = $t outside range [$tLeft..$tRight]")

      var iNext = i
      while (t <= tPrev && i > 0) {
        iNext = i
        i -= 1
        tNext = tPrev
        tPrev = if (i == 0) Int.MinValue else ts(i)
      }

      val tt = t
      t -= dt

      if (tt == tNext) { // no need to compute
        exact(tt, iNext)
      } else {
        approx(tt, Math.min(tt - tPrev, tNext - tt))
      }
    }
  }
}


/**
  * Floater Hormann interpolant for 1-dim time series
  */
class FHT1Interpolant (_t0: Long, _ts: Array[Int], val vs: Array[Double], _d: Int=3)
                    extends FHInterpolant(_t0, _ts, min(_d,_ts.length)) with T1Interpolant {

  /**
    * a single time point interpolation
    */
  def eval (tAbs: Long): Double = {
    val t: Int = (tAbs - t0).toInt
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval(t, -t, vs)
    } else if (j == N1) { // after last observation
      _eval(t, t - ts(N1), vs)
    } else {
      _eval(t, min(t - ts(j), ts(j+1) - t), vs)
    }
  }

  //--- result array interpolation

  def evalRange(tStart: Long, tEnd: Long, dt: Int): Array[Double] = {
    val len = 1 + (tEnd - tStart).toInt / dt
    val result = new Array[Double](len)
    var i = 0
    evalForward(tStart, tEnd, dt) { (t,v) =>
      result(i) = v
      i += 1
    }
    result
  }

  def evalHeadInto(result: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val l = 1 + (tEnd - tStart).toInt / dt
    val te = if (l > result.length) tStart + (result.length-1) * dt else tEnd
    var i = 0
    evalForward(tStart, te, dt) { (t,v) =>
      result(i) = v
      i += 1
    }
    i
  }
  def evalReverseTailInto(result: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val l = 1 + (tEnd - tStart).toInt / dt
    val ts = if (l > result.length) tEnd - (result.length-1) * dt else tStart
    var i = 0
    evalReverse(tEnd, ts, dt) { (t,v) =>
      result(i) = v
      i += 1
    }
    i
  }

  //--- iteration

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    val i = findLeftIndex(tLeft)
    val tPrev = if (i < 0) Int.MinValue else ts(i)
    val tNext = if (i == N1) Int.MaxValue else ts(i+1)

    try {
      _evalForward(i, tLeft, tPrev, tNext, tRight, dt) { (t, j) =>
        f(t, vs(j))
      } { (t, dist) =>
        f(t, _eval(t, dist, vs))
      }
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double)=>Unit): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    val i = findLeftIndex(tRight)
    val tPrev = if (i < 0) Int.MinValue else ts(i)
    val tNext = if (i == N1) Int.MaxValue else ts(i+1)

    try {
      _evalReverse(i, tRight, tPrev, tNext, tLeft, dt) { (t, j) =>
        f(t, vs(j))
      } { (t, dist) =>
        f(t, _eval(t, dist, vs))
      }
    } catch {
      case _: BreakException => // do nothing
    }
  }

  //--- iterators
  type Result = T2[Long,Double]  // the iterator next() type

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val ld = new Result(0,0)
    def exact (t: Int, i: Int): Result = { ld.updated(t,vs(i)) }
    def approx (t: Int, dist: Int): Result = { ld.updated(t,_eval(t,dist,vs)) }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val ld = new Result(0,0)
    def exact (t: Int, i: Int): Result = { ld.updated(t,vs(i)) }
    def approx (t: Int, dist: Int): Result = { ld.updated(t,_eval(t,dist,vs)) }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Result] = {
    val tEnd = ts(N1) + t0
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Result] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}

/**
  * Floater Hormann interpolant for 2-dim time series
  */
class FHT2Interpolant (_t0: Long, _ts: Array[Int], val vx: Array[Double], val vy: Array[Double], _d: Int=3)
                                                            extends FHInterpolant(_t0, _ts, min(_d,_ts.length)) {
  type Result = T3[Long,Double,Double]

  @inline protected final def _eval2 (t: Int, dist: Int, result: Result): Unit = {
    val n1 = N1
    val w = this.w
    val ts = this.ts

    var sDenom, sx, sy: Double = 0.0
    var i = 0
    while (i <= n1){
      val wd = (w(i) * dist) / (t - ts(i))
      sDenom += wd

      sx += wd * vx(i)
      sy += wd * vy(i)

      i += 1
    }

    result._1 = t + t0
    result._2 = sx / sDenom
    result._2 = sy / sDenom
  }

  def eval (tAbs: Long, result: Result): Unit = {
    val t: Int = (tAbs - t0).toInt
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval2(t, -t, result)
    } else if (j == N1) { // after last observation
      _eval2(t, t - ts(N1), result)
    } else {
      _eval2(t, min(t - ts(j), ts(j+1) - t), result)
    }
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double)=>Unit): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    val i = findLeftIndex(tLeft)
    val tPrev = if (i < 0) Int.MinValue else ts(i)
    val tNext = if (i == N1) Int.MaxValue else ts(i+1)
    val result = new Result(0, 0.0, 0.0)

    def exact (t: Int, i: Int): Unit = f(t + t0, vx(i), vy(i))
    def approx (t: Int, dist: Int): Unit = {
      _eval2(t, dist, result)
      f(t + t0, result._2, result._3)
    }

    try {
      _evalForward(i, tLeft, tPrev, tNext, tRight, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double)=>Unit): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    val i = findLeftIndex(tRight)
    val tPrev = if (i < 0) Int.MinValue else ts(i)
    val tNext = if (i == N1) Int.MaxValue else ts(i+1)
    val result = new Result(0, 0.0, 0.0)

    def exact (t: Int, i: Int): Unit = f(t + t0, vx(i), vy(i))
    def approx (t: Int, dist: Int): Unit = {
      _eval2(t, dist, result)
      f(t + t0, result._2, result._3)
    }

    try {
      _evalReverse(i, tRight, tPrev, tNext, tLeft, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalRange2(tStart: Long, tEnd: Long, dt: Int): (Array[Double],Array[Double]) = {
    val len = 1 + (tEnd - tStart).toInt / dt
    val rx = new Array[Double](len)
    val ry = new Array[Double](len)
    var i = 0
    evalForward(tStart, tEnd, dt) { (t,x,y) =>
      rx(i) = x
      ry(i) = y
      i += 1
    }
    (rx,ry)
  }

  def evalHeadInto(rx: Array[Double], ry: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val nr = min(rx.length, ry.length)
    val l = 1 + (tEnd - tStart).toInt / dt
    val te = if (l > nr) tStart + (nr-1) * dt else tEnd
    var i = 0
    evalForward(tStart, te, dt) { (t,x,y) =>
      rx(i) = x
      ry(i) = y
      i += 1
    }
    i
  }
  def evalReverseTailInto(rx: Array[Double], ry: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val nr = min(rx.length, ry.length)

    val l = 1 + (tEnd - tStart).toInt / dt
    val ts = if (l > nr) tEnd - (nr-1) * dt else tStart
    var i = 0
    evalReverse(tEnd, ts, dt) { (t,x,y) =>
      rx(i) = x
      ry(i) = y
      i += 1
    }
    i
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0.0,0.0)
    def exact (t: Int, i: Int): Result = { result.updated(t,vx(i),vy(i)) }
    def approx (t: Int, dist: Int): Result = { _eval2(t,dist,result); result }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0.0,0.0)
    def exact (t: Int, i: Int): Result = { result.updated(t,vx(i),vy(i)) }
    def approx (t: Int, dist: Int): Result = { _eval2(t,dist,result); result }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Result] = {
    val tEnd = ts(N1) + t0
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Result] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}


/**
  * Floater Hormann interpolant for 3-dim time series
  */
class FHT3Interpolant (_t0: Long, _ts: Array[Int], val vx: Array[Double], val vy: Array[Double], val vz: Array[Double], _d: Int=3)
                                extends FHInterpolant(_t0, _ts, min(_d,_ts.length)) {
  type Result = T4[Long,Double,Double,Double]

  @inline protected final def _eval3 (t: Int, dist: Int, result: Result): Unit = {
    val n1 = N1
    val w = this.w
    val ts = this.ts

    var sDenom, sx, sy, sz: Double = 0.0
    var i = 0
    while (i <= n1){
      val wd = (w(i) * dist) / (t - ts(i))
      sDenom += wd

      sx += wd * vx(i)
      sy += wd * vy(i)
      sz += wd * vz(i)

      i += 1
    }

    result._1 = t + t0
    result._2 = sx / sDenom
    result._2 = sy / sDenom
    result._3 = sz / sDenom
  }

  def eval (tAbs: Long, result: Result): Unit = {
    val t: Int = (tAbs - t0).toInt
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval3(t, -t, result)
    } else if (j == N1) { // after last observation
      _eval3(t, t - ts(N1), result)
    } else {
      _eval3(t, min(t - ts(j), ts(j+1) - t), result)
    }
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    val i = findLeftIndex(tLeft)
    val tPrev = if (i < 0) Int.MinValue else ts(i)
    val tNext = if (i == N1) Int.MaxValue else ts(i+1)
    val result = new Result(0, 0.0, 0.0, 0.0)

    def exact (t: Int, i: Int): Unit = f(t + t0, vx(i), vy(i), vz(i))
    def approx (t: Int, dist: Int): Unit = {
      _eval3(t, dist, result)
      f(t + t0, result._2, result._3, result._4)
    }

    try {
      _evalForward(i, tLeft, tPrev, tNext, tRight, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit): Unit = {
    val tLeft: Int = (tStart - t0).toInt
    val tRight: Int = (tEnd - t0).toInt

    val i = findLeftIndex(tRight)
    val tPrev = if (i < 0) Int.MinValue else ts(i)
    val tNext = if (i == N1) Int.MaxValue else ts(i+1)
    val result = new Result(0, 0.0, 0.0, 0.0)

    def exact (t: Int, i: Int): Unit = f(t + t0, vx(i), vy(i), vz(i))
    def approx (t: Int, dist: Int): Unit = {
      _eval3(t, dist, result)
      f(t + t0, result._2, result._3, result._4)
    }

    try {
      _evalReverse(i, tRight, tPrev, tNext, tLeft, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalRange2(tStart: Long, tEnd: Long, dt: Int): (Array[Double],Array[Double],Array[Double]) = {
    val len = 1 + (tEnd - tStart).toInt / dt
    val rx = new Array[Double](len)
    val ry = new Array[Double](len)
    val rz = new Array[Double](len)

    var i = 0
    evalForward(tStart, tEnd, dt) { (t,x,y,z) =>
      rx(i) = x
      ry(i) = y
      rz(i) = z
      i += 1
    }
    (rx,ry,rz)
  }

  def evalHeadInto(rx: Array[Double], ry: Array[Double], rz: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val nr = min(min(rx.length, ry.length),rz.length)
    val l = 1 + (tEnd - tStart).toInt / dt
    val te = if (l > nr) tStart + (nr-1) * dt else tEnd
    var i = 0
    evalForward(tStart, te, dt) { (t,x,y,z) =>
      rx(i) = x
      ry(i) = y
      rz(i) = z
      i += 1
    }
    i
  }
  def evalReverseTailInto(rx: Array[Double], ry: Array[Double], rz: Array[Double], tStart: Long, tEnd: Long, dt: Int): Int = {
    val nr = min(min(rx.length, ry.length),rz.length)
    val l = 1 + (tEnd - tStart).toInt / dt
    val ts = if (l > nr) tEnd - (nr-1) * dt else tStart
    var i = 0
    evalReverse(tEnd, ts, dt) { (t,x,y,z) =>
      rx(i) = x
      ry(i) = y
      rz(i) = z
      i += 1
    }
    i
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0.0,0.0, 0.0)
    def exact (t: Int, i: Int): Result = { result.updated(t,vx(i),vy(i),vz(i)) }
    def approx (t: Int, dist: Int): Result = { _eval3(t,dist,result); result }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0.0,0.0, 0.0)
    def exact (t: Int, i: Int): Result = { result.updated(t,vx(i),vy(i),vz(i)) }
    def approx (t: Int, dist: Int): Result = { _eval3(t,dist,result); result }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Result] = {
    val tEnd = ts(N1) + t0
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Result] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}