/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.util

import Nat._
import gov.nasa.race.uom.DateTime

/**
  * a mutable type that represents a multi-dimensional data point consisting of a date and a ordered
  * set of Double values that can be accessed by index
  */
trait TDataPoint[N<:Nat] {
  var millis: Long
  def length: Int

  def apply (i: Int): Double
  def update (i: Int, d: Double): Unit
  def clear: Unit

  def date: DateTime = DateTime.ofEpochMillis(millis)
  def setDate(date: DateTime): Unit = millis = date.toEpochMillis
  def getTime: Long = millis
  def setTime(millis: Long): Unit = this.millis = millis

  def *= (d: Double): Unit
  def /= (d: Double): Unit

  def := (v: TDataPoint[N]): Unit
  def += (v: TDataPoint[N]): Unit
  def -= (v: TDataPoint[N]): Unit

  // generic string conversion
  override def toString: String = {
    val s = new StringBuffer
    s.append("TDataPoint")
    s.append('[')
    s.append(length)
    s.append("](")
    s.append(millis)
    val n = length
    for (i <- 0 until n) {
      s.append(',')
      s.append(apply(i))
    }
    s.append(')')
    s.toString
  }
}

//--- specialized types for a number of Nats

// note these are not final so that we can derive classes that add semantics
// note computations should use SIMD if a stable compiler becomes available

/**
  * DataPoint with a single Double value
  */
class TDataPoint1 (var millis: Long, var _0: Double) extends TDataPoint[N1] {
  type Self = TDataPoint1

  def length = 1
  def apply (i: Int): Double = {
    if (i == 0) _0 else throw new ArrayIndexOutOfBoundsException(i)
  }
  def update (i: Int, d: Double): Unit = {
    if (i == 0) _0 = d else throw new ArrayIndexOutOfBoundsException(i)
  }
  def clear: Unit = {
    millis = 0
    _0 = 0
  }

  def *= (d: Double): Unit = _0 *= d
  def /= (d: Double): Unit = _0 /= d

  def := (v: TDataPoint[N1]): Unit = {
    millis = v.millis
    v match {
      case p: Self => _0 = p._0
      case _ => _0 = v(0)
    }
  }
  def += (v: TDataPoint[N1]): Unit = {
    v match {
      case p: Self => _0 += p._0
      case _ => _0 += v(0)
    }
  }
  def -= (v: TDataPoint[N1]): Unit = {
    v match {
      case p: Self => _0 -= p._0
      case _ => _0 -= v(0)
    }
  }

  def set(t: Long, d: Double): this.type = {
    millis = t
    _0 = d
    this
  }
}

object TDataPoint1 {
  def unapply (p: TDataPoint1): Option[(Long,Double)] = Some((p.millis, p._0))
}

/**
  * DataPoint with 2 Double values
  */
class TDataPoint2 (var millis: Long, var _0: Double, var _1: Double) extends TDataPoint[N2] {
  type Self = TDataPoint2

  def length = 2
  def apply (i: Int): Double = {
    i match {
      case 0 => _0
      case 1 => _1
      case _ => throw new ArrayIndexOutOfBoundsException(i)
    }
  }
  def update (i: Int, d: Double): Unit = {
    i match {
      case 0 => _0 = d
      case 1 => _1 = d
      case _ => throw new ArrayIndexOutOfBoundsException(i)
    }
  }
  def clear: Unit = {
    millis = 0
    _0 = 0
    _1 = 0
  }

  def *= (d: Double): Unit = {
    _0 *= d
    _1 *= d
  }
  def /= (d: Double): Unit = {
    _0 /= d
    _1 /= d
  }

  def := (v: TDataPoint[N2]): Unit = {
    millis = v.millis
    v match {
      case p: Self =>
        _0 = p._0
        _1 = p._1
      case _ =>
        _0 = v(0)
        _1 = v(1)
    }
  }
  def += (v: TDataPoint[N2]): Unit = {
    v match {
      case p: Self =>
        _0 += p._0
        _1 += p._1
      case _ =>
        _0 += v(0)
        _1 += v(1)
    }
  }
  def -= (v: TDataPoint[N2]): Unit = {
    v match {
      case p: Self =>
        _0 -= p._0
        _1 -= p._1
      case _ =>
        _0 -= v(0)
        _1 -= v(1)
    }
  }

  def set(t: Long, v0: Double, v1: Double): this.type = {
    millis = t
    _0 = v0
    _1 = v1
    this
  }
}

object TDataPoint2 {
  def unapply (p: TDataPoint2): Option[(Long,Double,Double)] = Some((p.millis, p._0, p._1))
}

/**
  * DataPoint with 3 Double values
  */
class TDataPoint3 (var millis: Long, var _0: Double, var _1: Double, var _2: Double) extends TDataPoint[N3] {
  type Self = TDataPoint3

  def length = 3
  def apply (i: Int): Double = {
    i match {
      case 0 => _0
      case 1 => _1
      case 2 => _2
      case _ => throw new ArrayIndexOutOfBoundsException(i)
    }
  }
  def update (i: Int, d: Double): Unit = {
    i match {
      case 0 => _0 = d
      case 1 => _1 = d
      case 2 => _2 = d
      case _ => throw new ArrayIndexOutOfBoundsException(i)
    }
  }
  def clear: Unit = {
    millis = 0
    _0 = 0
    _1 = 0
    _2 = 0
  }

  def *= (d: Double): Unit = {
    _0 *= d
    _1 *= d
    _2 *= d
  }
  def /= (d: Double): Unit = {
    _0 /= d
    _1 /= d
    _2 /= d
  }

  def := (v: TDataPoint[N3]): Unit = {
    millis = v.millis
    v match {
      case p: Self =>
        _0 = p._0
        _1 = p._1
        _2 = p._2
      case _ =>
        _0 = v(0)
        _1 = v(1)
        _2 = v(2)
    }
  }
  def += (v: TDataPoint[N3]): Unit = {
    v match {
      case p: Self =>
        _0 += p._0
        _1 += p._1
        _2 += p._2
      case _ =>
        _0 += v(0)
        _1 += v(1)
        _2 += v(2)
    }
  }
  def -= (v: TDataPoint[N3]): Unit = {
    v match {
      case p: Self =>
        _0 -= p._0
        _1 -= p._1
        _2 -= p._2
      case _ =>
        _0 -= v(0)
        _1 -= v(1)
        _2 -= v(2)
    }
  }

  def set(t: Long, v0: Double, v1: Double, v2: Double): this.type = {
    millis = t
    _0 = v0
    _1 = v1
    _2 = v2
    this
  }

  def update (p: TDataPoint3): Unit = {
    millis = p.millis
    _0 = p._0
    _1 = p._1
    _2 = p._2
  }
}

object TDataPoint3 {
  def unapply (p: TDataPoint3): Option[(Long,Double,Double,Double)] = Some((p.millis, p._0, p._1, p._2))
}

//... and more specialized implementations to follow

/**
  * a generic, array based DataPoint
  * note that operations are still type safe in terms of cardinality N
  */
class ArrayTDataPoint[N<:Nat](var millis: Long)(implicit nat: N) extends TDataPoint[N] {
  type Self = ArrayTDataPoint[N]

  val length = nat.toInt
  val data = new Array[Double](length)

  def apply (i: Int): Double = data(i)
  def update (i: Int, x: Double): Unit = data(i) = x
  def clear: Unit = {
    util.Arrays.fill(data,0)
  }

  def *= (d: Double): Unit = {
    var i = 0
    while (i < length) { data(i) *= d; i += 1 }
  }

  def /= (d: Double): Unit = {
    var i = 0
    while (i < length) { data(i) /= d; i += 1 }
  }

  def := (v: TDataPoint[N]): Unit = {
    v match {
      case p: Self => System.arraycopy(p.data,0,data,0,length)
      case _ =>
        var i = 0
        while (i < length) { data(i) = v(i); i += 1 }
    }
  }

  def += (v: TDataPoint[N]): Unit = {
    v match {
      case p: Self =>
        var i = 0
        while (i < length) { data(i) += p.data(i); i += 1 }
      case _ =>
        var i = 0
        while (i < length) { data(i) += v(i); i += 1 }
    }
  }

  def -= (v: TDataPoint[N]): Unit = {
    v match {
      case p: Self =>
        var i = 0
        while (i < length) { data(i) -= p.data(i); i += 1 }
      case _ =>
        var i = 0
        while (i < length) { data(i) -= v(i); i += 1 }
    }
  }
}