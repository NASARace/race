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

import TInterpolant._

/**
  * abstract Interpolant that uses barycentric rational interpolation according to
  * M. Floater, K. Hormann: "Barycentric rational interpolation with no poles and high rates of approximation"
  * https://www.inf.usi.ch/hormann/papers/Floater.2007.BRI.pdf
  *
  * Note that we do not imply a specific time or value storage but require an accessor that maps logical indices
  * [0..n-1] to time values of observations
  */
abstract class FHTInterpolant (n: Int, val d: Int)(getT: (Int)=>Long) extends TInterpolant(n)(getT) {

  val w: Array[Double] = new Array(n)  // barycentric weights
  calcWeights

  protected def calcWeights: Unit = {
    var sign: Int = if (d % 2 > 0) -1 else 1

    var k = 0
    while (k <= n1) {
      var s: Double = 0
      val tk = getT(k)
      val iMin = max(k - d, 0)
      val iMax = min(k, n1 - d)
      var i = iMin
      while (i <= iMax) {
        var v: Double = 1
        val jMax = i + d
        var j = i
        while (j < k)     { v /= tk - getT(j); j += 1 }
        j += 1
        while (j <= jMax) { v /= getT(j) - tk; j += 1 }
        s += v
        i += 1
      }
      w(k) = sign * s
      sign = -sign
      k += 1
    }
  }
}

class FHT1 (ts: Array[Long], vs: Array[Double], d: Int = 3) extends FHT1Interpolant(ts.length,d)(ts(_))(vs(_))

/**
  * Floater Hormann interpolant for 1-dim time series
  */
class FHT1Interpolant (n: Int, d: Int=3)
                      (getT: (Int)=>Long)
                      (getValue: (Int)=>Double) extends FHTInterpolant(n,d)(getT) with T1Interpolant {

  @inline protected final def _eval1(t: Long, dist: Long): Double = {
    var sumNoms: Double = 0
    var sumDenoms: Double = 0
    var i = 0
    while (i <= n1){
      val wd = (w(i) * dist) / (t - getT(i))
      sumNoms += wd * getValue(i)
      sumDenoms += wd
      i += 1
    }
    sumNoms / sumDenoms
  }

  /**
    * a single time point interpolation
    */
  def eval (t: Long): Double = {
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval1(t, -t)
    } else if (j == n1) { // after last observation
      _eval1(t, t - getT(n1))
    } else {
      _eval1(t, min(t - getT(j), getT(j+1) - t))
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
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)

    def exact (t: Long, i: Int) = f(t,getValue(i))
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = f(t, _eval1(t, min(t - tPrev, tNext - t)))

    try {
      _evalForward(i, tStart, tPrev, tNext, tEnd, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double)=>Unit): Unit = {
    val i = findLeftIndex(tEnd)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)

    def exact (t: Long, i: Int) = f(t,getValue(i))
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = f(t, _eval1(t, min(t-tPrev, tNext-t)))

    try {
      _evalReverse(i, tEnd, tPrev, tNext, tStart, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  //--- iterators
  type Result = T2[Long,Double]  // the iterator next() type

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val ld = new Result(0,0)
    def exact (t: Long, i: Int): Result = { ld.updated(t,getValue(i)) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = { ld.updated(t,_eval1(t,min(t-tPrev, tNext-t))) }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val ld = new Result(0,0)
    def exact (t: Long, i: Int): Result = { ld.updated(t,getValue(i)) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = { ld.updated(t,_eval1(t,min(t-tPrev, tNext-t))) }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Result] = {
    val tEnd = tRight
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
class FHT2Interpolant (n: Int, d: Int=3)
                      (getT: (Int)=>Long)
                      (getDataPoint: (Int,Data2)=>Data2) extends FHTInterpolant(n,d)(getT) with T2Interpolant {

  @inline protected final def _eval2 (t: Long, dist: Long, dataPoint: Data2): Data2 = {
    var sDenom, sx, sy: Double = 0.0
    var i = 0
    while (i <= n1){
      val data = getDataPoint(i,dataPoint)

      val wd = (w(i) * dist) / (t - data._1)
      sDenom += wd

      sx += wd * data._2
      sy += wd * data._3

      i += 1
    }

    dataPoint.updated(t, sx/sDenom, sy/sDenom)
  }

  def eval (t: Long, result: Data2): Unit = {
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval2(t, -t, result)
    } else if (j == n1) { // after last observation
      _eval2(t, t - tRight, result)
    } else {
      _eval2(t, min(t - getT(j), getT(j+1) - t), result)
    }
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)
    val dataPoint = new Data2(0, 0.0, 0.0)

    def exact (t: Long, i: Int): Unit = {
      val data = getDataPoint(i,dataPoint)
      f(t, data._2, data._3)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Unit = {
      val dist = min(t-tPrev, tNext-t)
      val data = _eval2(t, dist, dataPoint)
      f(t, data._2, data._3)
    }

    try {
      _evalForward(i, tLeft, tPrev, tNext, tRight, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tEnd)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)
    val dataPoint = new Data2(0, 0.0, 0.0)

    def exact (t: Long, i: Int): Unit = {
      val data = getDataPoint(i,dataPoint)
      f(t, data._2, data._3)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Unit = {
      val dist = min(t-tPrev, tNext-t)
      val data = _eval2(t, dist, dataPoint)
      f(t, data._2, data._3)
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

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Data2] = {
    val dataPoint = new Data2(0,0.0,0.0)
    def exact (t: Long, i: Int): Data2 = { getDataPoint(i,dataPoint) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Data2 = { _eval2(t,min(t-tPrev, tNext-t),dataPoint); dataPoint }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Data2] = {
    val dataPoint = new Data2(0,0.0,0.0)
    def exact (t: Long, i: Int): Data2 = { getDataPoint(i,dataPoint) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Data2 = { _eval2(t,min(t-tPrev, tNext-t),dataPoint); dataPoint }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Data2] = {
    val tEnd = tRight
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Data2] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}


/**
  * Floater Hormann interpolant for 3-dim time series
  */
class FHT3Interpolant (n: Int, d: Int=3)
                      (getT: (Int)=>Long)
                      (getDataPoint: (Int,Data3)=>Data3) extends FHTInterpolant(n,d)(getT) with T3Interpolant {

  @inline protected final def _eval3 (t: Long, dist: Long, dataPoint: Data3): Data3 = {
    var sDenom, sx, sy, sz: Double = 0.0
    var i = 0
    while (i <= n1){
      val data = getDataPoint(i,dataPoint)

      val wd = (w(i) * dist) / (t - data._1)
      sDenom += wd

      sx += wd * data._2
      sy += wd * data._3
      sz += wd * data._4

      i += 1
    }

    dataPoint.updated(t, sx/sDenom, sy/sDenom, sz/sDenom)
  }

  def eval (t: Long, result: Data3): Unit = {
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval3(t, -t, result)
    } else if (j == n1) { // after last observation
      _eval3(t, t - tRight, result)
    } else {
      _eval3(t, min(t - getT(j), getT(j+1) - t), result)
    }
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)
    val dataPoint = new Data3(0, 0.0, 0.0, 0.0)

    def exact (t: Long, i: Int): Unit = {
      val data = getDataPoint(i,dataPoint)
      f(t, data._2, data._3, data._4)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Unit = {
      val dist = min(t-tPrev, tNext-t)
      val data = _eval3(t, dist, dataPoint)
      f(t, data._2, data._3, data._4)
    }

    try {
      _evalForward(i, tLeft, tPrev, tNext, tRight, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tEnd)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)
    val dataPoint = new Data3(0, 0.0, 0.0, 0.0)

    def exact (t: Long, i: Int): Unit = {
      val data = getDataPoint(i,dataPoint)
      f(t, data._2, data._3, data._4)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Unit = {
      val dist = min(t-tPrev, tNext-t)
      val data = _eval3(t, dist, dataPoint)
      f(t, data._2, data._3, data._4)
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

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Data3] = {
    val dataPoint = new Data3(0, 0.0,0.0,0.0)
    def exact (t: Long, i: Int): Data3 = { getDataPoint(i,dataPoint) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Data3 = { _eval3(t,min(t-tPrev, tNext-t),dataPoint); dataPoint }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Data3] = {
    val dataPoint = new Data3(0, 0.0,0.0,0.0)
    def exact (t: Long, i: Int): Data3 = { getDataPoint(i,dataPoint) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Data3 = { _eval3(t,min(t-tPrev, tNext-t),dataPoint); dataPoint }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Data3] = {
    val tEnd = tRight
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Data3] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}

/**
  * a FHTInterpolant that supports interpolation for a generic number of dimensions
  * (unfortunately Scala does not support type level Ints)
  */
class FHTnInterpolant (n: Int, getT: (Int)=>Long, getV: Array[(Int)=>Double], d: Int=3)
                         extends FHTInterpolant(n,d)(getT) with TnInterpolant {

  type Result = Array[Double]

  @inline protected final def _eval (t: Long, dist: Long, result: Array[Double], m: Int): Unit = {
    var sDenom = 0.0
    val s: Array[Double] = new Array(m)
    var i = 0
    while (i <= n1){
      val wd = (w(i) * dist) / (t - getT(i))
      sDenom += wd

      var j=0
      while (j < m) {
        s(j) += wd * getV(j)(i)
        j += 1
      }

      i += 1
    }

    var j=0
    while (j < m){
      result(j) = s(j) / sDenom
      j += 1
    }
  }

  def eval (t: Long, result: Array[Double]): Unit = {
    val m = min(result.length, getV.length)
    val j = findLeftIndex(t)

    if (j < 0) { // before first observation
      _eval(t, -t, result, m)
    } else if (j == n1) { // after last observation
      _eval(t, t - tRight, result, m)
    } else {
      _eval(t, min(t - getT(j), getT(j+1) - t), result, m)
    }
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val m = getV.length
    val result = new Array[Double](m)

    def exact (t: Long, i: Int): Result = {
      var j = 0
      while (j < m){
        result(j) = getV(j)(i)
        j += 1
      }
      result
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      val dist = min(t-tPrev, tNext-t)
      _eval(t,dist,result,m)
      result
    }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val m = getV.length
    val result = new Array[Double](m)

    def exact (t: Long, i: Int): Result = {
      var j = 0
      while (j < m){
        result(j) = getV(j)(i)
        j += 1
      }
      result
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      val dist = min(t-tPrev, tNext-t)
      _eval(t,dist,result,m)
      result
    }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Result] = {
    val tEnd = tRight
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Result] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}
