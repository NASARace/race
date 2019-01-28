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

import gov.nasa.race.common._

import scala.collection.mutable.ArrayBuffer
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

class Speed protected[uom] (val d: Double) extends AnyVal {

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

  @inline def ≈ (x: Speed)(implicit εSpeed: Speed) = Math.abs(d - x.d) <= εSpeed.d
  @inline def ~= (x: Speed)(implicit εSpeed: Speed) = Math.abs(d - x.d) <= εSpeed.d
  @inline def within (x: Speed, distance: Speed) = Math.abs(d - x.d) <= distance.d

  @inline def < (x: Speed) = d < x.d
  @inline def <= (x: Speed) = d <= x.d
  @inline def > (x: Speed) = d > x.d
  @inline def >= (x: Speed) = d >= x.d
  @inline def =:= (x: Speed) = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Speed) = d == x.d

  @inline def compare (other: Length): Int = d compare other.d

  // we intentionally omit ==, <=, >=

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline def isUndefined = d.isNaN
  @inline def isDefined = !d.isNaN
  @inline def orElse(fallback: Speed) = if (isDefined) this else fallback

  override def toString = show
  def show = s"${d}m/s"
  def showKmh = s"${toKmh}kmh"
  def showMph = s"${toMph}mph"
  def showKnots = s"${toKnots}kn"

}

object SpeedArray {
  def MetersPerSecondArray (a: Array[Double]): SpeedArray = new SpeedArray(a.clone())
  def FeetPerSecondArray (a: Array[Double]): SpeedArray = new SpeedArray(UOMArray.initData(a,MetersPerSecInFps))
  def KnotsArray (a: Array[Double]): SpeedArray = new SpeedArray(UOMArray.initData(a,MetersPerSecInKnot))
}

/**
  * wrapper class for arrays of Angles without per element allocation
  * TODO - needs more Array methods
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class SpeedArray protected[uom] (protected[uom] val data: Array[Double]) extends UOMArray {

  def this(len: Int) = this(new Array[Double](len))

  @inline def apply(i:Int): Speed = MetersPerSecond(data(i))
  @inline def update(i:Int, a: Speed): Unit = data(i) = a.toMetersPerSecond
  @inline def foreach(f: (Speed)=>Unit): Unit = data.foreach( d=> f(MetersPerSecond(d)))

  @inline def slice(from: Int, until: Int): SpeedArray = new SpeedArray(data.slice(from,until))
  @inline def tail: SpeedArray = new SpeedArray(data.tail)
  @inline def take (n: Int): SpeedArray = new SpeedArray(data.take(n))
  @inline def drop (n: Int): SpeedArray = new SpeedArray(data.drop(n))

  @inline def copyToArray(other: SpeedArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: SpeedArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: SpeedArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Speed = MetersPerSecond(data.last)
  @inline def exists(p: (Speed)=>Boolean): Boolean = data.exists( d=> p(MetersPerSecond(d)))
  @inline def count(p: (Speed)=>Boolean): Int = data.count( d=> p(MetersPerSecond(d)))
  @inline def max: Speed = MetersPerSecond(data.max)
  @inline def min: Speed = MetersPerSecond(data.min)

  def toBuffer: SpeedArrayBuffer = SpeedArrayBuffer.MetersPerSecondArrayBuffer(data)
  def toKnotsArray: Array[Double] = toDoubleArray(MetersPerSecInKnot)
  def toFeetPerSecondArray: Array[Double] = toDoubleArray( MetersPerSecInFps)
  def toMetersPerSecondArray: Array[Double] = data.clone()
}

object SpeedArrayBuffer {
  def MetersPerSecondArrayBuffer (vs: Seq[Double]): SpeedArrayBuffer = new SpeedArrayBuffer(UOMArrayBuffer.initData(vs))
  def FeetPerSecondArrayBuffer (vs: Seq[Double]): SpeedArrayBuffer = new SpeedArrayBuffer(UOMArrayBuffer.initData(vs,MetersPerSecInFps))
  def KnotsArrayBuffer (vs: Seq[Double]): SpeedArrayBuffer = new SpeedArrayBuffer(UOMArrayBuffer.initData(vs,MetersPerSecInKnot))
}

final class SpeedArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Double]) extends UOMArrayBuffer {
  private class SpeedIterator (it: Iterator[Double]) extends Iterator[Speed] {
    @inline def hasNext: Boolean = it.hasNext
    @inline def next: Speed = MetersPerSecond(it.next)
  }

  def this (initialSize: Int) = this(new ArrayBuffer[Double](initialSize))

  @inline def += (v: Speed): SpeedArrayBuffer = { data += v.toMetersPerSecond; this }
  @inline def append (vs: Speed*): Unit = vs.foreach( data += _.toMetersPerSecond )

  @inline def apply(i:Int): Speed = MetersPerSecond(data(i))
  @inline def update(i:Int, a: Speed): Unit = data(i) = a.toMetersPerSecond
  @inline def foreach(f: (Speed)=>Unit): Unit = data.foreach( d=> f(MetersPerSecond(d)))
  @inline def iterator: Iterator[Speed] = new SpeedIterator(data.iterator)
  @inline def reverseIterator: Iterator[Speed] = new SpeedIterator(data.reverseIterator)

  @inline def slice(from: Int, until: Int): SpeedArrayBuffer = new SpeedArrayBuffer(data.slice(from,until))
  @inline def tail: SpeedArrayBuffer = new SpeedArrayBuffer(data.tail)
  @inline def take (n: Int): SpeedArrayBuffer = new SpeedArrayBuffer(data.take(n))
  @inline def drop (n: Int): SpeedArrayBuffer = new SpeedArrayBuffer(data.drop(n))

  @inline def copyToArray(other: SpeedArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: SpeedArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: SpeedArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Speed = MetersPerSecond(data.last)
  @inline def exists(p: (Speed)=>Boolean): Boolean = data.exists( d=> p(MetersPerSecond(d)))
  @inline def count(p: (Speed)=>Boolean): Int = data.count( d=> p(MetersPerSecond(d)))
  @inline def max: Speed = MetersPerSecond(data.max)
  @inline def min: Speed = MetersPerSecond(data.min)

  def toArray: SpeedArray = new SpeedArray(data.toArray)
  def toMetersPerSecondArray: Array[Double] = data.toArray
  def toKnotsArray: Array[Double] = toDoubleArray(MetersPerSecInKnot)
  def toFeetPerSecondArray: Array[Double] = toDoubleArray(MetersPerSecInFps)
}