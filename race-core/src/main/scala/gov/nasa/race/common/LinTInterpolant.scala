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
  * linear interpolation for 1-dim time series
  *
  * TODO - needs to be refactored to avoid redundancy for T2,T3
  */
class LinT1Interpolant (n: Int)
                       (getT: (Int)=>Long)
                       (getValue: (Int)=>Double) extends TInterpolant(n)(getT) with T1Interpolant {

  def eval (t: Long): Double = {
    if (t < tLeft) { // before first observation -> extrapolate
      val t1 = getT(0)
      val t2 = getT(1)
      val v1 = getValue(0)
      val v2 = getValue(1)
      v1 - (t1 - t) * (v2 - v1) / (t2 - t1)

    } else if (t > tRight) { // after last observation -> extrapolate
      val t1 = getT(n1-1)
      val t2 = getT(n1)
      val v1 = getValue(n1-1)
      val v2 = getValue(n1)
      v2 + (t - t2) * (v2 - v1) / (t2 - t1)

    } else {
      val i = findLeftIndex(t)
      val t1 = getT(i)
      if (t == t1) return getValue(i)

      val i1 = i+1
      val t2 = getT(i1)
      val v1 = getValue(i)
      val v2 = getValue(i1)
      v1 + (t - t1) * (v2 - v1) / (t2 - t1)
    }
  }

  def evalForward (tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit): Unit = {
    val i = findLeftIndex(tStart)
    val tPrev = if (i < 0) Int.MinValue else getT(i)
    val tNext = if (i == n1) Int.MaxValue else getT(i+1)

    def exact (t: Long, i: Int) = {
      f(t,getValue(i))
    }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int) = {
      if (i < 0 || (i >= n1)) {
        f(t, eval(t))  // we don't care for optimization here
      } else {
        val v1 = getValue(i)
        val v2 = getValue(i+1)
        f(t, v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev))
      }
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
      val v = if (i < 0 || (i >= n1)) {
        eval(t)  // we don't care for optimization here
      } else {
        val v1 = getValue(i)
        val v2 = getValue(i+1)
        v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev)
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
      if (i < 0 || (i >= n1)) {
        f(t, eval(t))  // we don't care for optimization here
      } else {
        val v1 = getValue(i)
        val v2 = getValue(i+1)
        f(t, v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev))
      }
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
      val v = if (i < 0 || (i >= n1)) {
        eval(t)  // we don't care for optimization here
      } else {
        val v1 = getValue(i)
        val v2 = getValue(i+1)
        v1 + (t - tPrev) * (v2 - v1) / (tNext - tPrev)
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
