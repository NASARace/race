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

import scala.annotation.tailrec

object TInterpolant {
  type Data1 = T2[Long,Double]
  type Data2 = T3[Long,Double,Double]
  type Data3 = T4[Long,Double,Double,Double]
}
import TInterpolant._

/**
  * base class for time based, storage independent interpolants
  */
abstract class TInterpolant (val n: Int)(getT: (Int)=>Long) {
  protected[this] class BreakException extends Exception
  protected[this] val breakException = new BreakException

  val n1 = n-1
  val tLeft = getT(0)
  val tRight = getT(n1)

  protected final def findLeftIndex (t: Long): Int = {
    if (t < tLeft) {  // lower than start -> no left index
      -1
    } else if (t >= tRight) {  // higher or equal to end -> last observation
      n1
    }
    else {
      var a = 0
      var b = n1
      while (b - a > 1){
        val c = (a + b)/2;
        val tc = getT(c);
        if (t == tc) return c
        else if (t > tc) a = c
        else b = c
      }
      a
    }
  }

  /**
    * use from within loop callbacks to break iteration
    */
  def break: Unit = throw breakException

  @tailrec protected final def _evalForward (_iPrev: Int, t: Long, _tPrev: Long, _tNext: Long, tEnd: Long, dt: Int)
                                            (exact: (Long,Int)=>Unit)
                                            (approx: (Long,Long,Long,Int)=>Unit): Unit = {
    if (t <= tEnd) {
      var iPrev = _iPrev
      var tPrev = _tPrev
      var tNext = _tNext

      while (t >= tNext && iPrev <= n1) {
        iPrev += 1
        tPrev = tNext
        tNext = if (iPrev >= n1) Int.MaxValue else getT(iPrev+1)
      }

      if (t == tPrev) { // no need to compute
        exact(t, iPrev)
      } else {
        approx(tPrev,t,tNext,iPrev)
      }

      _evalForward(iPrev, t + dt, tPrev, tNext, tEnd, dt)(exact)(approx)
    }
  }

  /**
    * iterator version - can be used to iterate over several functions at the same time
    *
    * Note this is less efficint than _evalForward since it stores the iteration state on
    * the heap (fields). While the source looks very redudant to _evalForward, the bytecode does not
    */
  protected[this] class ForwardIterator[T] (tStart: Long, tEnd: Long, dt: Int)
                                           (exact: (Long,Int)=>T)
                                           (approx: (Long,Long,Long,Int)=>T) extends Iterator[T] {
    var iPrev = findLeftIndex(tStart)
    var tPrev = if (iPrev < 0) Int.MinValue else getT(iPrev)
    var tNext = if (iPrev == n1) Int.MaxValue else getT(iPrev+1)
    var t = tStart

    override def hasNext: Boolean = t <= tEnd

    override def next(): T = {
      if (t > tEnd) throw new NoSuchElementException(s"t = $t outside range [$tStart..$tEnd]")

      while (t >= tNext && iPrev <= n1) {
        iPrev += 1
        tPrev = tNext
        tNext = if (iPrev >= n1) Int.MaxValue else getT(iPrev+1)
      }

      val tt = t
      t += dt

      if (tt == tPrev) { // no need to compute
        exact(tt, iPrev)
      } else {
        approx(tPrev,tt,tNext,iPrev)
      }
    }
  }

  @tailrec protected final def _evalReverse (_iPrev: Int, t: Long, _tPrev: Long, _tNext: Long, tLeft: Long, dt: Int)
                                            (exact: (Long,Int)=>Unit)
                                            (approx: (Long,Long,Long,Int)=>Unit): Unit = {
    if (t >= tLeft) {
      var iPrev = _iPrev
      var tPrev = _tPrev
      var tNext = _tNext

      while (t < tPrev && iPrev >= 0) {
        iPrev -= 1
        tNext = tPrev
        tPrev = if (iPrev < 0) Int.MinValue else getT(iPrev)
      }

      if (t == tPrev) {
        exact(t, iPrev)
      } else {
        approx(tPrev,t,tNext,iPrev)
      }

      _evalReverse(iPrev, t - dt, tPrev, tNext, tLeft, dt)(exact)(approx)
    }
  }

  protected[this] class ReverseIterator[T] (tEnd: Long, tStart: Long, dt: Int)
                                           (exact: (Long,Int)=>T)
                                           (approx: (Long,Long,Long,Int)=>T) extends Iterator[T] {
    var iPrev = findLeftIndex(tEnd)
    var tPrev = if (iPrev < 0) Int.MinValue else getT(iPrev)
    var tNext = if (iPrev == n1) Int.MaxValue else getT(iPrev + 1)
    var t = tEnd

    override def hasNext: Boolean = t >= tStart

    override def next(): T = {
      if (t < tStart) throw new NoSuchElementException(s"t = $t outside range [$tStart..$tEnd]")

      while (t < tPrev && iPrev >= 0) {
        iPrev -= 1
        tNext = tPrev
        tPrev = if (iPrev < 0) Int.MinValue else getT(iPrev)
      }

      val tt = t
      t -= dt

      if (tt == tPrev) { // no need to compute
        exact(tt, iPrev)
      } else {
        approx(tPrev,tt,tNext,iPrev)
      }
    }
  }
}

trait T1Interpolant {
  def eval (t: Long): Double

  def evalForward(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit)
  def evalReverse(tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double)=>Unit)

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Data1]
  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Data1]

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Data1]
  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Data1]
}

trait T2Interpolant {
  def eval (t: Long, result: Data2): Unit

  def evalForward(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double)=>Unit)
  def evalReverse(tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double)=>Unit)

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Data2]
  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Data2]

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Data2]
  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Data2]
}

trait T3Interpolant {
  def eval (t: Long, result: Data3): Unit

  def evalForward(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit)
  def evalReverse(tEnd: Long, tStart: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit)

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Data3]
  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Data3]

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Data3]
  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Data3]
}

trait TnInterpolant {
  def eval (t: Long, result: Array[Double]): Unit

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[Array[Double]]
  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[Array[Double]]

  def reverseTailDurationIterator (dur: Long, dt: Int): Iterator[Array[Double]]
  def reverseTailIterator (tEnd: Long, dur: Long, dt: Int): Iterator[Array[Double]]
}