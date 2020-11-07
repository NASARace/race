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

import gov.nasa.race.common.Nat.{N1, N2, N3}


/**
  * the owner of data to interpolate
  */
trait TDataSource[N<:Nat,T<:TDataPoint[N]] {
  def size: Int                // number of data points
  def getT(i: Int): Long       // get time of observation point with logical index i
  def newDataPoint: T          // create a new zero'ed DataPoint object
  def getDataPoint(i: Int, dp: T): T  // set provided DataPoint values to observation i, using provided cache
}

//--- convenience types
trait TDataSource1 extends TDataSource[N1,TDataPoint1]
trait TDataSource2 extends TDataSource[N2,TDataPoint2]
trait TDataSource3 extends TDataSource[N3,TDataPoint3]
//... and more


//--- array based convenience sources (mostly for testing)

class ArrayTDataSource1 (val ts: Array[Long], val v0: Array[Double]) extends TDataSource[N1,TDataPoint1]{
  val size = ts.length
  assert(size == v0.length)
  def getT(i: Int) = ts(i)
  def newDataPoint = new TDataPoint1(0,0)
  def getDataPoint(i: Int, dp: TDataPoint1) = dp.set(ts(i), v0(i))
}

class ArrayTDataSource2 (val ts: Array[Long], val v0: Array[Double], val v1: Array[Double])
                                                                 extends TDataSource[N2,TDataPoint2]{
  val size = ts.length
  assert(size == v0.length)
  assert(size == v1.length)
  def getT(i: Int) = ts(i)
  def newDataPoint = new TDataPoint2(0,0, 0)
  def getDataPoint(i: Int, dp: TDataPoint2) = dp.set(ts(i), v0(i), v1(i))
}

class ArrayTDataSource3 (val ts: Array[Long], val v0: Array[Double], val v1: Array[Double], val v2: Array[Double])
                                                                 extends TDataSource[N3,TDataPoint3]{
  val size = ts.length
  assert(size == v0.length)
  assert(size == v1.length)
  assert(size == v2.length)

  def getT(i: Int) = ts(i)
  def newDataPoint = new TDataPoint3(0,0, 0,0)
  def getDataPoint(i: Int, dp: TDataPoint3) = dp.set(ts(i), v0(i), v1(i), v2(i))
}


/**
  * base class for multi-dimensional time series interpolants
  *
  * Note this class does not imply a interpolation algorithm (linear, rational etc.) or
  * DataPoint storage mechanism
  */
abstract class TInterpolant[N<:Nat,T<:TDataPoint[N]](val src: TDataSource[N,T]) {

  val n1 = src.size-1
  val tLeft = src.getT(0)
  val tRight = src.getT(n1)

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
        val tc = src.getT(c);
        if (t == tc) return c
        else if (t > tc) a = c
        else b = c
      }
      a
    }
  }

  //--- single time point

  def eval (t: Long): T

  //--- iterator support

  protected[this] class ForwardIterator[T] (tStart: Long, tEnd: Long, dt: Long)
                                           (exact: (Long,Int)=>T)
                                           (approx: (Long,Long,Long,Int)=>T) extends Iterator[T] {
    var iPrev = findLeftIndex(tStart)
    var tPrev = if (iPrev < 0) Long.MinValue else src.getT(iPrev)
    var tNext = if (iPrev == n1) Long.MaxValue else src.getT(iPrev+1)
    var t = tStart

    override def hasNext: Boolean = t <= tEnd

    override def next(): T = {
      if (t > tEnd) throw new NoSuchElementException(s"t = $t outside range [$tStart..$tEnd]")

      while (t >= tNext && iPrev <= n1) {
        iPrev += 1
        tPrev = tNext
        tNext = if (iPrev >= n1) Long.MaxValue else src.getT(iPrev+1)
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

  protected[this] class ReverseIterator[T] (tEnd: Long, tStart: Long, dt: Long)
                                           (exact: (Long,Int)=>T)
                                           (approx: (Long,Long,Long,Int)=>T) extends Iterator[T] {
    var iPrev = findLeftIndex(tEnd)
    var tPrev = if (iPrev < 0) Long.MinValue else src.getT(iPrev)
    var tNext = if (iPrev == n1) Long.MaxValue else src.getT(iPrev + 1)
    var t = tEnd

    override def hasNext: Boolean = t >= tStart

    override def next(): T = {
      if (t < tStart) throw new NoSuchElementException(s"t = $t outside range [$tStart..$tEnd]")

      while (t < tPrev && iPrev >= 0) {
        iPrev -= 1
        tNext = tPrev
        tPrev = if (iPrev < 0) Long.MinValue else src.getT(iPrev)
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

  def iterator (tStart: Long, tEnd: Long, dt: Long): Iterator[T]

  def reverseIterator (tEnd: Long, tStart: Long, dt: Long): Iterator[T]

  def reverseTailDurationIterator (dur: Long, dt: Long): Iterator[T] = {
    val tEnd = tRight
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }

  def reverseTailIterator (tEnd: Long, dur: Long, dt: Long): Iterator[T] = {
    val tStart = tEnd - dur
    reverseIterator(tEnd,tStart,dt)
  }
}
