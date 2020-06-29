/*
 * Copyright (c) 2016, United States Government, as represented by the
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
package gov.nasa.race.uom

import gov.nasa.race._
import gov.nasa.race.common._

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq
import scala.concurrent.duration.FiniteDuration


/**
  * speed quantities (magnitude of velocity vectors)
  * basis is m/s
  */
object Speed {
  //--- constants
  final val MetersPerSecInKnot = 1852.0 / 3600
  final val MetersPerSecInKmh = 1000.0 / 3600
  final val MetersPerSecInMph = 1609.344 / 3600
  final val MetersPerSecInFps = 0.3048
  final val MetersPerSecInFpm = 0.3048 / 60

  final val Speed0 = new Speed(0)
  final val UndefinedSpeed = new Speed(Double.NaN)
  @inline def isDefined(x: Speed): Boolean = !x.d.isNaN
  final implicit val εSpeed = MetersPerSecond(1e-10)

  def fromVxVy(vx: Speed, vy: Speed) = new Speed(Math.sqrt(squared(vx.d) + squared(vy.d)))

  //--- constructors
  @inline def MetersPerSecond(d: Double) = new Speed(d)
  @inline def FeetPerSecond(d: Double) = new Speed(d * MetersPerSecInFps)
  @inline def FeetPerMinute(d: Double) = new Speed(d * MetersPerSecInFpm)
  @inline def Knots(d: Double) = new Speed(d * MetersPerSecInKnot)
  @inline def KilometersPerHour(d: Double) = new Speed(d * MetersPerSecInKmh)
  @inline def UsMilesPerHour(d: Double) = new Speed(d * MetersPerSecInMph)

  implicit class SpeedConstructor (val d: Double) extends AnyVal {
    def `m/s` = MetersPerSecond(d)
    def kmh = KilometersPerHour(d)
    def `km/h` = KilometersPerHour(d)
    def mph = UsMilesPerHour(d)
    def knots = Knots(d)
    def kn = Knots(d)
    def fps = FeetPerSecond(d)
    def fpm = FeetPerMinute(d)
  }
}

import Speed._

class Speed protected[uom] (val d: Double) extends AnyVal
                                             with Ordered[Speed] with MaybeUndefined {

  @inline def toMetersPerSecond: Double = d
  @inline def toKnots: Double = d / MetersPerSecInKnot
  @inline def toKilometersPerHour: Double = d / MetersPerSecInKmh
  @inline def toKmh = toKilometersPerHour
  @inline def toUsMilesPerHour: Double = d / MetersPerSecInMph
  @inline def toFeetPerSecond: Double = d / MetersPerSecInFps
  @inline def toFeetPerMinute: Double = d / MetersPerSecInFpm
  @inline def toMph = toUsMilesPerHour

  @inline def + (x: Speed): Speed = new Speed(d + x.d)
  @inline def - (x: Speed): Speed = new Speed(d - x.d)

  @inline def * (x: Double): Speed = new Speed(d * x)
  @inline def / (x: Double): Speed = new Speed(d / x)

  @inline def * (t: FiniteDuration) = new Length((d * t.toMicros)/1e6)
  @inline def / (t: FiniteDuration) = new Acceleration((d / t.toMicros)*1e6)

  @inline def ≈ (x: Speed)(implicit εSpeed: Speed): Boolean = Math.abs(d - x.d) <= εSpeed.d
  @inline def ~= (x: Speed)(implicit εSpeed: Speed): Boolean = Math.abs(d - x.d) <= εSpeed.d
  @inline def within (x: Speed, distance: Speed): Boolean = Math.abs(d - x.d) <= distance.d

  @inline def =:= (x: Speed): Boolean = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Speed): Boolean = d == x.d

  @inline override def < (x: Speed): Boolean = d < x.d
  @inline override def <= (x: Speed): Boolean = d <= x.d
  @inline override def > (x: Speed): Boolean = d > x.d
  @inline override def >= (x: Speed): Boolean = d >= x.d
  @inline override def compare (other: Speed): Int = if (d > other.d) 1 else if (d < other.d) -1 else 0
  @inline override def compareTo (other: Speed): Int = compare(other)

  // we intentionally omit ==, <=, >=

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isDefined: Boolean = !d.isNaN
  @inline override def isUndefined: Boolean = d.isNaN

  override def toString = show
  def show = s"${d}m/s"
  def showKmh = s"${toKmh}kmh"
  def showMph = s"${toMph}mph"
  def showKnots = s"${toKnots}kn"

}

object SpeedArray {
  def MetersPerSecondArray (a: Array[Double]): SpeedArray = new SpeedArray(a.clone())
  def FeetPerSecondArray (a: Array[Double]): SpeedArray = new SpeedArray(UOMDoubleArray.initData(a,MetersPerSecInFps))
  def KnotsArray (a: Array[Double]): SpeedArray = new SpeedArray(UOMDoubleArray.initData(a,MetersPerSecInKnot))
}

// we need to define these explicitly since we otherwise call a conversion function for each value

private[uom] class SpeedIter (data: Seq[Double], first: Int, last: Int) extends Iterator[Speed] {
  private var i = first
  def hasNext: Boolean = (i <= last)
  def next(): Speed = {
    val j = i
    if (j > last) throw new NoSuchElementException
    i += 1
    new Speed(data(j))
  }
}

private[uom] class ReverseSpeedIter (data: Seq[Double], first: Int, last: Int) extends Iterator[Speed] {
  private var i = first
  def hasNext: Boolean = (i >= last)
  def next(): Speed = {
    val j = i
    if (j < last) throw new NoSuchElementException
    i -= 1
    new Speed(data(j))
  }
}

/**
  * wrapper class for arrays of Angles without per element allocation
  * TODO - needs more Array methods
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class SpeedArray protected[uom] (protected[uom] val data: Array[Double]) extends UOMDoubleArray[Speed] {
  import scala.math.Ordering.Double.TotalOrdering

  type Self = SpeedArray
  type SelfBuffer = SpeedArrayBuffer

  def this(len: Int) = this(new Array[Double](len))

  override def grow(newCapacity: Int): SpeedArray = {
    val newData = new Array[Double](newCapacity)
    System.arraycopy(data,0,newData,0,data.length)
    new SpeedArray(newData)
  }

  @inline override def copyFrom(other: Self, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline override def apply(i:Int): Speed = new Speed(data(i))
  @inline override def update(i:Int, a: Speed): Unit = data(i) = a.toMetersPerSecond
  @inline override def foreach(f: (Speed)=>Unit): Unit = data.foreach( d=> f(new Speed(d)))

  @inline override def iterator: Iterator[Speed] = new SpeedIter(data,0,data.length-1)
  @inline override def reverseIterator: Iterator[Speed] = new ReverseSpeedIter(data,data.length-1,0)

  @inline override def slice(from: Int, until: Int): Self = new SpeedArray(data.slice(from,until))
  @inline override def tail: Self = new SpeedArray(data.tail)
  @inline override def take (n: Int): Self = new SpeedArray(data.take(n))
  @inline override def drop (n: Int): Self = new SpeedArray(data.drop(n))

  @inline override def last: Speed = new Speed(data.last)
  @inline override def exists(p: (Speed)=>Boolean): Boolean = data.exists( d=> p(new Speed(d)))
  @inline override def count(p: (Speed)=>Boolean): Int = data.count( d=> p(new Speed(d)))
  @inline override def max: Speed = new Speed(data.max)
  @inline override def min: Speed = new Speed(data.min)

  override def toBuffer: SelfBuffer = SpeedArrayBuffer.MetersPerSecondArrayBuffer(data)

  def toKnotsArray: Array[Double] = toDoubleArray(MetersPerSecInKnot)
  def toFeetPerSecondArray: Array[Double] = toDoubleArray( MetersPerSecInFps)
  def toMetersPerSecondArray: Array[Double] = data.clone()
}

object SpeedArrayBuffer {
  def MetersPerSecondArrayBuffer (vs: Seq[Double]): SpeedArrayBuffer = new SpeedArrayBuffer(UOMDoubleArrayBuffer.initData(vs))
  def FeetPerSecondArrayBuffer (vs: Seq[Double]): SpeedArrayBuffer = new SpeedArrayBuffer(UOMDoubleArrayBuffer.initData(vs,MetersPerSecInFps))
  def KnotsArrayBuffer (vs: Seq[Double]): SpeedArrayBuffer = new SpeedArrayBuffer(UOMDoubleArrayBuffer.initData(vs,MetersPerSecInKnot))
}

final class SpeedArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Double]) extends UOMDoubleArrayBuffer[Speed] {
  import scala.math.Ordering.Double.TotalOrdering

  type Self = SpeedArrayBuffer
  type SelfArray = SpeedArray

  def this (initialSize: Int) = this(new ArrayBuffer[Double](initialSize))

  @inline override def += (v: Speed): Self = { data += v.toMetersPerSecond; this }
  @inline override def append (vs: Speed*): Unit = vs.foreach( data += _.toMetersPerSecond )

  @inline override def apply(i:Int): Speed = new Speed(data(i))
  @inline override def update(i:Int, a: Speed): Unit = data(i) = a.toMetersPerSecond
  @inline override def foreach(f: (Speed)=>Unit): Unit = data.foreach( d=> f(new Speed(d)))

  @inline override def iterator: Iterator[Speed] = new SpeedIter(data,0,data.size-1)
  @inline override def reverseIterator: Iterator[Speed] = new ReverseSpeedIter(data,data.size-1,0)

  @inline override def slice(from: Int, until: Int): Self = new SpeedArrayBuffer(data.slice(from,until))
  @inline override def tail: Self = new SpeedArrayBuffer(data.tail)
  @inline override def take (n: Int): Self = new SpeedArrayBuffer(data.take(n))
  @inline override def drop (n: Int): Self = new SpeedArrayBuffer(data.drop(n))

  @inline def copyToArray(other: SelfArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: SelfArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: SelfArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline override def last: Speed = new Speed(data.last)
  @inline override def exists(p: (Speed)=>Boolean): Boolean = data.exists( d=> p(new Speed(d)))
  @inline override def count(p: (Speed)=>Boolean): Int = data.count( d=> p(new Speed(d)))
  @inline override def max: Speed = new Speed(data.max)
  @inline override def min: Speed = new Speed(data.min)

  override def toArray: SelfArray = new SpeedArray(data.toArray)

  def toMetersPerSecondArray: Array[Double] = data.toArray
  def toKnotsArray: Array[Double] = toDoubleArray(MetersPerSecInKnot)
  def toFeetPerSecondArray: Array[Double] = toDoubleArray(MetersPerSecInFps)
}

// TODO - we should also provide DeltaSpeedArray/Buffer