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

import gov.nasa.race.common.TInterpolant.{Data2, Data3}

/**
  * linear interpolation for 1-dim time series
  *
  * TODO - needs to be refactored to avoid redundancy for T2,T3
  */
class LinT1Interpolant (n: Int)
                       (getT: (Int)=>Long)
                       (getValue: (Int)=>Double) extends TInterpolant(n)(getT) with T1Interpolant {

  private def _extrapolateLeft (t: Long): Double = {
    val t1 = getT(0)
    val t2 = getT(1)
    val v1 = getValue(0)
    val v2 = getValue(1)
    v1 - (t1 - t) * (v2 - v1) / (t2 - t1)
  }

  private def _extrapolateRight (t: Long): Double = {
    val t1 = getT(n1-1)
    val t2 = getT(n1)
    val v1 = getValue(n1-1)
    val v2 = getValue(n1)
    v2 + (t - t2) * (v2 - v1) / (t2 - t1)
  }

  private def _interpolate (i: Int, t: Long): Double = {
    val t1 = getT(i)
    val v1 = getValue(i)
    if (t == t1) return v1

    val i1 = i+1
    val t2 = getT(i1)
    val v2 = getValue(i1)

    v1 + (t - t1) * (v2 - v1) / (t2 - t1)
  }

  def eval (t: Long): Double = {
    if (t >= tLeft) {
      if (t <= tRight) _interpolate(findLeftIndex(t), t)
      else _extrapolateRight(t)
    } else _extrapolateLeft(t)
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit): Unit = {
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)

    def exact (t: Long, i: Int) = {
      f(t,getValue(i))
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i >= 0) {
        if (i < n1) {
          val v1 = getValue(i)
          val v2 = getValue(i+1)
          val v = v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev)
          f(t, v)
        } else f(t,_extrapolateRight(t))
      } else f(t, _extrapolateLeft(t))
    }

    try {
      _evalForward(i, tStart, tPrev, tNext, tEnd, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  type Result = T2[Long,Double]  // the iterator next() type

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val ld = new Result(0,0)
    def exact (t: Long, i: Int): Result = { ld.updated(t,getValue(i)) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      val v = {
        if (i >= 0) {
          if (i < n1) {
            val v1 = getValue(i)
            val v2 = getValue(i+1)
            v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev)
          } else _extrapolateRight(t)
        } else _extrapolateLeft(t)
      }
      ld.updated(t,v)
    }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double)=>Unit): Unit = {
    val i = findLeftIndex(tEnd)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)

    def exact (t: Long, i: Int) = f(t,getValue(i))
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i >= 0) {
        if (i < n1) {
          val v1 = getValue(i)
          val v2 = getValue(i+1)
          val v = v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev)
          f(t, v)
        } else f(t,_extrapolateRight(t))
      } else f(t, _extrapolateLeft(t))
    }

    try {
      _evalReverse(i, tEnd, tPrev, tNext, tStart, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val ld = new Result(0,0)
    def exact (t: Long, i: Int): Result = { ld.updated(t,getValue(i)) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      val v = {
        if (i >= 0) {
          if (i < n1) {
            val v1 = getValue(i)
            val v2 = getValue(i+1)
            v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev)
          } else _extrapolateRight(t)
        } else _extrapolateLeft(t)
      }
      ld.updated(t,v)
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

class LinT1 (ts: Array[Long], vs: Array[Double]) extends LinT1Interpolant(ts.length)(ts(_))(vs(_))


class LinT2Interpolant (n: Int)
                       (getT: (Int)=>Long)
                       (getDataPoint: (Int,Data2)=>Data2) extends TInterpolant(n)(getT) with T2Interpolant {

  type Result = Data2

  private def _extrapolateLeft (t: Long, result: Result): Unit = {
    var p = getDataPoint(0,result)
    val t1 = p._1
    val x1 = p._2
    val y1 = p._3

    p = getDataPoint(1,result)
    val t2 = p._1
    val x2 = p._2
    val y2 = p._3

    val dt = t1 - t
    val h = t2 - t1

    val x = x1 - dt * (x2 - x1) / h
    val y = y1 - dt * (y2 - y1) / h

    result.update(t, x, y)
  }

  private def _extrapolateRight (t: Long, result: Result): Unit = {
    var p = getDataPoint(n1-1,result)
    val t1 = p._1
    val x1 = p._2
    val y1 = p._3

    p = getDataPoint(n1,result)
    val t2 = p._1
    val x2 = p._2
    val y2 = p._3

    val dt = t - t2
    val h = t2 - t1

    val x = x2 - dt * (x2 - x1) / h
    val y = y2 - dt * (y2 - y1) / h

    result.update(t, x, y)
  }

  private def _interpolate (i: Int, t: Long, result: Result): Unit = {
    var p = getDataPoint(i,result)
    if (t > p._1) { // otherwise we are already done
      val t1 = p._1
      val x1 = p._2
      val y1 = p._3

      p = getDataPoint(i + 1, result)
      val t2 = p._1
      val x2 = p._2
      val y2 = p._3

      val dt = t - t1
      val h = t2 - t1

      val x = x1 + dt * (x2 - x1) / h
      val y = y1 + dt * (y2 - y1) / h

      result.update(t, x, y)
    }
  }

  def eval (t: Long, result: Result): Unit = {
    if (t >= tLeft) {
      if (t <= tRight) _interpolate( findLeftIndex(t), t,result)
      else _extrapolateRight(t,result)
    } else _extrapolateLeft(t,result)
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)
    val p = new Result(0, 0.0, 0.0)

    def exact (t: Long, i: Int) = {
      getDataPoint(i,p)
      f(t,p._2,p._3)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i >= 0) {
        if (i <= n1) _interpolate(i, t, p) else _extrapolateRight(t,p)
      } else _extrapolateLeft(t,p)
      f(t, p._2,p._3)
    }

    try {
      _evalForward(i, tStart, tPrev, tNext, tEnd, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tEnd)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i + 1)
    val p = new Result(0, 0.0, 0.0)

    def exact (t: Long, i: Int) = {
      getDataPoint(i,p)
      f(t,p._2,p._3)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i >= 0) {
        if (i <= n1) _interpolate(i, t, p) else _extrapolateRight(t,p)
      } else _extrapolateLeft(t,p)
      f(t, p._2,p._3)
    }

    try {
      _evalReverse(i, tEnd, tPrev, tNext, tStart, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0, 0)
    def exact (t: Long, i: Int): Result = { getDataPoint(i,result) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      if (i >= 0) {
        if (i < n1)  _interpolate(i, t, result) else _extrapolateRight(t,result)
      } else _extrapolateLeft(t,result)
      result
    }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0, 0)
    def exact (t: Long, i: Int): Result = { getDataPoint(i,result) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      if (i >= 0) {
        if (i < n1)  _interpolate(i, t, result) else _extrapolateRight(t,result)
      } else _extrapolateLeft(t,result)
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

class LinT2 (ts: Array[Long], xs: Array[Double], ys: Array[Double])
  extends LinT2Interpolant(ts.length)(ts(_))(xs(_))(ys(_))


class LinT3Interpolant (n: Int)
                       (getT: (Int)=>Long)
                       (getDataPoint: (Int,Data3)=>Data3) extends TInterpolant(n)(getT) with T3Interpolant {

  type Result = Data3

  private def _extrapolateLeft (t: Long, result: Result): Unit = {
    var p = getDataPoint(0,result)
    val t1 = p._1
    val x1 = p._2
    val y1 = p._3
    val z1 = p._4

    p = getDataPoint(1,result)
    val t2 = p._1
    val x2 = p._2
    val y2 = p._3
    val z2 = p._4

    val dt = t1 - t
    val h = t2 - t1

    val x = x1 - dt * (x2 - x1) / h
    val y = y1 - dt * (y2 - y1) / h
    val z = z1 - dt * (z2 - z1) / h

    result.update(t, x, y, z)
  }

  private def _extrapolateRight (t: Long, result: Result): Unit = {
    var p = getDataPoint(n1-1,result)
    val t1 = p._1
    val x1 = p._2
    val y1 = p._3
    val z1 = p._4

    p = getDataPoint(n1,result)
    val t2 = p._1
    val x2 = p._2
    val y2 = p._3
    val z2 = p._4

    val dt = t - t2
    val h = t2 - t1

    val x = x2 - dt * (x2 - x1) / h
    val y = y2 - dt * (y2 - y1) / h
    val z = z2 - dt * (z2 - z1) / h

    result.update(t, x, y, z)
  }

  private def _interpolate (i: Int, t: Long, result: Result): Unit = {
    var p = getDataPoint(i,result)
    if (t > p._1) { // otherwise we are already done
      val t1 = p._1
      val x1 = p._2
      val y1 = p._3
      val z1 = p._4

      p = getDataPoint(i + 1, result)
      val t2 = p._1
      val x2 = p._2
      val y2 = p._3
      val z2 = p._4

      val dt = t - t1
      val h = t2 - t1

      val x = x1 + dt * (x2 - x1) / h
      val y = y1 + dt * (y2 - y1) / h
      val z = z1 + dt * (z2 - z1) / h

      result.update(t, x, y, z)
    }
  }

  def eval (t: Long, result: Result): Unit = {
    if (t >= tLeft) {
      if (t <= tRight) _interpolate( findLeftIndex(t), t,result)
      else _extrapolateRight(t,result)
    } else _extrapolateLeft(t,result)
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)
    val p = new Result(0, 0.0, 0.0, 0.0)

    def exact (t: Long, i: Int) = {
      getDataPoint(i,p)
      f(t,p._2,p._3,p._4)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i >= 0) {
        if (i <= n1) _interpolate(i, t, p) else _extrapolateRight(t,p)
      } else _extrapolateLeft(t,p)
      f(t, p._2,p._3,p._4)
    }

    try {
      _evalForward(i, tStart, tPrev, tNext, tEnd, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def evalReverse (tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit): Unit = {
    val i = findLeftIndex(tEnd)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i + 1)
    val p = new Result(0, 0.0, 0.0, 0.0)

    def exact (t: Long, i: Int) = {
      getDataPoint(i,p)
      f(t,p._2,p._3,p._4)
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i >= 0) {
        if (i <= n1) _interpolate(i, t, p) else _extrapolateRight(t,p)
      } else _extrapolateLeft(t,p)
      f(t, p._2,p._3,p._4)
    }

    try {
      _evalReverse(i, tEnd, tPrev, tNext, tStart, dt)(exact)(approx)
    } catch {
      case _: BreakException => // do nothing
    }
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0.0, 0.0, 0.0)
    def exact (t: Long, i: Int): Result = { getDataPoint(i,result) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      if (i >= 0) {
        if (i < n1)  _interpolate(i, t, result) else _extrapolateRight(t,result)
      } else _extrapolateLeft(t,result)
      result
    }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Result] = {
    val result = new Result(0,0.0, 0.0, 0.0)
    def exact (t: Long, i: Int): Result = { getDataPoint(i,result) }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): Result = {
      if (i >= 0) {
        if (i < n1)  _interpolate(i, t, result) else _extrapolateRight(t,result)
      } else _extrapolateLeft(t,result)
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

class LinT3 (ts: Array[Long], xs: Array[Double], ys: Array[Double], zs: Array[Double])
  extends LinT2Interpolant(ts.length)(ts(_))(xs(_))(ys(_))(zs(_))