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

import Math._

import gov.nasa.race._
import gov.nasa.race.common._
import gov.nasa.race.util.ArrayUtils

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq
import scala.language.postfixOps

/**
  * angle quantities
  * underlying unit is radians
  */
object Angle {

  //--- constants
  final val π_2 = Math.PI/2.0
  final val π = Math.PI
  final val π3_2 = 3 * π_2
  final val π2 = π * 2.0

  final val DegreesInRadian = π / 180.0
  final val MinutesInRadian = DegreesInRadian * 60.0

  final val Angle0 = new Angle(0)
  final val Angle90 = new Angle(π_2)
  final val HalfPi = Angle90
  final val AngleNeg90 = new Angle(-π_2)
  final val NegativeHalfPi = AngleNeg90
  final val Angle180 = new Angle(π)
  final val Pi = Angle180
  final val AngleNeg180 = new Angle(-π)
  final val NegativePi = AngleNeg180
  final val Angle270 = new Angle(π3_2)
  final val TwoPi = new Angle(π2)

  final val UndefinedAngle = new Angle(Double.NaN)
  @inline def isDefined(x: Angle): Boolean  = !x.d.isNaN

  final implicit val εAngle = Degrees(1.0e-10)  // provide your own if application specific
  def fromVxVy (vx: Speed, vy: Speed) = Radians(normalizeRadians2Pi(Math.atan2(vx.d, vy.d)))

  //--- utilities
  @inline def normalizeRadians (d: Double): Double = d - π2 * Math.floor((d + π) / π2) // -π..π
  @inline def normalizeRadians2Pi (d: Double): Double = if (d<0) d % π2 + π2 else d % π2  // 0..2π
  @inline def normalizeDegrees (d: Double): Double =  if (d < 0) d % 360 + 360 else d % 360 // 0..360
  @inline def normalizeDegrees180 (d: Double): Double = {
    var nd = d % 360.0
    nd = (nd + 360.0) % 360.0
    if (nd > 180.0) nd -= 360.0
    nd
  }
  @inline def normalizedDegreesToRadians (d: Double): Double = normalizeDegrees(d) * DegreesInRadian

  @inline def degreesToRadians (deg: Double): Double = deg * DegreesInRadian

  @inline def absDiff(a1: Angle, a2: Angle): Angle = {
    val d1 = normalizeRadians2Pi(a1.d)
    val d2 = normalizeRadians2Pi(a2.d)
    val dd = abs(d1 - d2)
    if (dd > π) Radians(π2 - dd) else Radians(dd)
  }

  //--- trigonometrics functions
  // note we have to use upper case here because we otherwise get ambiguity errors when
  // importing both Angle._ and Math._ versions. This is a consequence of using a AnyVal Angle

  @inline def Sin(a:Angle): Double = sin(a.d)
  @inline def Sin2(a:Angle): Double = Sin(a)`²`
  @inline def Cos(a:Angle): Double = cos(a.d)
  @inline def Cos2(a:Angle): Double = Cos(a)`²`
  @inline def Tan(a:Angle): Double = tan(a.d)
  @inline def Tan2(a:Angle): Double = Tan(a)`²`

  @inline def Asin(x: Double): Angle = Radians(asin(x))
  @inline def Acos(x: Double): Angle = Radians(acos(x))
  @inline def Atan(x: Double): Angle = Radians(atan(x))

  //--- Angle constructors
  @inline def Degrees (d: Double): Angle = new Angle(d * DegreesInRadian)
  @inline def Radians (d: Double): Angle = new Angle(d)

  implicit class AngleConstructor (val d: Double) extends AnyVal {
    @inline def degrees = Degrees(d)
    @inline def radians = Radians(d)
  }

  //--- to support expressions with a leading unit-less numeric factor
  implicit class AngleDoubleFactor (val d: Double) extends AnyVal {
    @inline def * (x: Angle) = new Angle(x.d * d)
  }
  implicit class AngleIntFactor (val d: Int) extends AnyVal {
    @inline def * (x: Angle) = new Angle(x.d * d)
  }
}
import Angle._

// basis is radians
class Angle protected[uom] (val d: Double) extends AnyVal with MaybeUndefined {

  //---  Double converters
  @inline def toRadians: Double = d
  @inline def toDegrees: Double = d / DegreesInRadian
  @inline def toNormalizedDegrees: Double = normalizeDegrees(toDegrees)
  @inline def toNormalizedDegrees180: Double = normalizeDegrees180(toDegrees)

  def toNormalizedHalfPi: Angle = { // -π/2..π/2  - useful for latitude TODO - not very efficient
    if (d >= -π_2 && d <= π_2)  this  // already normalized
    else {
      val nd = normalizeRadians2Pi(d)
      if (nd > π_2 && nd <= π) new Angle( π_2 - nd)  // S
      else if (nd > π && nd <= π3_2) new Angle( π - nd)  // S
      else new Angle( d - π3_2) // N
    }
  }
  def toNormalizedLatitude: Angle = toNormalizedHalfPi

  def toNormalizedPi: Angle = { // -π..π
    if (d >= -π && d <= π)  this  // already normalized
    else new Angle( normalizeRadians(d))
  }
  def toNormalizedLongitude: Angle = toNormalizedPi

  @inline def toRoundedDegrees: Int = toNormalizedDegrees.round.toInt
  @inline def toRoundedDegrees180: Int = toNormalizedDegrees180.round.toInt

  @inline def abs = new Angle(Math.abs(d))
  @inline def negative = new Angle(-d)

  //--- numeric and comparison operators (those normalize to 0..2pi)
  @inline def + (x: Angle): Angle = new Angle( normalizeRadians2Pi(d + x.d))
  @inline def - (x: Angle): Angle = new Angle( normalizeRadians2Pi(d - x.d))

  @inline def * (x: Double): Angle = new Angle( normalizeRadians2Pi(d * x))
  @inline def / (x: Double): Angle = new Angle( normalizeRadians2Pi(d / x))
  @inline def / (x: Angle)(implicit r: AngleDisambiguator.type): Double = d / x.d

  @inline def ≈ (x: Angle)(implicit εAngle: Angle) = Math.abs(d - x.d) <= εAngle.d
  @inline def ~= (x: Angle)(implicit εAngle: Angle) = Math.abs(d - x.d) <= εAngle.d
  @inline def withinTolerance(x: Angle, tolerance: Angle) = {
    Math.abs( normalizeRadians( normalizeRadians(d) - normalizeRadians(x.d))) <= tolerance.d
  }

  @inline def within(min: Angle, max: Angle): Boolean = (this >= min) && (this <= max)

  // use only for sub-range
  @inline def < (x: Angle) = d < x.d
  @inline def <= (x: Angle) = d <= x.d
  @inline def > (x: Angle) = d > x.d
  @inline def >= (x: Angle) = d >= x.d

  @inline def =:= (x: Angle) = d == x.d // use this if you really mean equality
  @inline def ≡ (x: Angle) = d == x.d
  // we intentionally omit ==, <=, >=

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isDefined = !d.isNaN
  @inline override def isUndefined = d.isNaN

  //--- string converters
  override def toString = show // NOTE - calling this will cause allocation, use 'show'
  def show: String = s"${toNormalizedDegrees}°"
  def showRounded: String = f"${toNormalizedDegrees}%.0f°"
  def showRounded5: String = f"${toNormalizedDegrees}%.5f°"
}

//-------------- element allocation free Angle collections
// note that while we could factor out most of the delegation methods into a trait that is
// based on an abstract Seq[Angle] this would make it harder (if not impossible) for the compiler to inline
// (Array[T] needs a implicit conversion for the Seq[T] interface)

object AngleArray {
  def DegreesArray (a: Array[Double]): AngleArray = new AngleArray(UOMDoubleArray.initData(a,DegreesInRadian))
  def RadiansArray (a: Array[Double]): AngleArray = new AngleArray(a.clone())
}


// we need to define these explicitly since we otherwise call a conversion function for each value

private[uom] class AngleIter (data: Seq[Double], first: Int, last: Int) extends Iterator[Angle] {
  private var i = first
  def hasNext: Boolean = (i <= last)
  def next(): Angle = {
    val j = i
    if (j > last) throw new NoSuchElementException
    i += 1
    new Angle(data(j))
  }
}

private[uom] class ReverseAngleIter (data: Seq[Double], first: Int, last: Int) extends Iterator[Angle] {
  private var i = first
  def hasNext: Boolean = (i >= last)
  def next(): Angle = {
    val j = i
    if (j < last) throw new NoSuchElementException
    i -= 1
    new Angle(data(j))
  }
}

/**
  * wrapper class for arrays of Angles without per element allocation
  * note that Array[T] is final hence we cannot extend it
  *
  * TODO - implement scala.collection.Seq[Angle]
  */
final class AngleArray protected[uom] (protected[uom] val data: Array[Double]) extends UOMDoubleArray[Angle] {
  import scala.math.Ordering.Double.TotalOrdering

  type Self = AngleArray
  type SelfBuffer = AngleArrayBuffer

  def this(len: Int) = this(new Array[Double](len))

  override def grow(newCapacity: Int): AngleArray = new AngleArray(ArrayUtils.grow(data,newCapacity))

  @inline override def copyFrom(other: Self, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline override def apply(i:Int): Angle = new Angle(data(i))
  @inline override def update(i:Int, a: Angle): Unit = data(i) = a.toRadians
  @inline override def foreach(f: (Angle)=>Unit): Unit = data.foreach( d=> f(new Angle(d)))
  @inline override def iterator: Iterator[Angle] = new AngleIter(data,0,data.size-1)
  @inline override def reverseIterator: Iterator[Angle] = new ReverseAngleIter(data,data.size-1,0)

  @inline override def slice(from: Int, until: Int): Self = new AngleArray(data.slice(from,until))
  @inline override def tail: Self = new AngleArray(data.tail)
  @inline override def take (n: Int): Self = new AngleArray(data.take(n))
  @inline override def drop (n: Int): Self = new AngleArray(data.drop(n))

  @inline override def last: Angle = Radians(data.last)
  @inline override def exists(p: (Angle)=>Boolean): Boolean = data.exists( d=> p(new Angle(d)))
  @inline override def count(p: (Angle)=>Boolean): Int = data.count( d=> p(new Angle(d)))
  @inline override def max: Angle = new Angle(data.max)
  @inline override def min: Angle = new Angle(data.min)

  override def toBuffer: SelfBuffer = AngleArrayBuffer.RadiansArrayBuffer(data)

  def toDegreesArray: Array[Double] = toDoubleArray(DegreesInRadian)
  def toRadiansArray: Array[Double] = data.clone()
}


object AngleArrayBuffer {
  def DegreesArrayBuffer (as: Seq[Double]): AngleArrayBuffer = new AngleArrayBuffer(UOMDoubleArrayBuffer.initData(as,DegreesInRadian))
  def RadiansArrayBuffer (as: Seq[Double]): AngleArrayBuffer = new AngleArrayBuffer(UOMDoubleArrayBuffer.initData(as))
}

/**
  * Wrapper class for resizable arrays of Angle values without per element allocation
  * TODO - implement scala.collection.mutable.Seq[Angle]
  *
  * note - while we could extend ArrayBuffer instead of using delegation, this would either
  * expose the underlying predefined type (Double - if we extend ArrayBuffer[Double]),
  * or cause a reference element type despite Angle being a AnyVal (if we extend
  * ArrayBuffer[Angle])
  */
final class AngleArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Double]) extends UOMDoubleArrayBuffer[Angle] {
  import scala.math.Ordering.Double.TotalOrdering

  type Self = AngleArrayBuffer
  type SelfArray = AngleArray

  def this (initialSize: Int) = this(new ArrayBuffer[Double](initialSize))

  @inline override def += (v: Angle): Self = { data += v.toRadians; this }
  @inline override def append (vs: Angle*): Unit = vs.foreach( data += _.toRadians )

  @inline override def apply(i:Int): Angle = new Angle(data(i))
  @inline override def update(i:Int, a: Angle): Unit = data(i) = a.toRadians
  @inline override def foreach(f: (Angle)=>Unit): Unit = data.foreach( d=> f(new Angle(d)))
  @inline override def iterator: Iterator[Angle] = new AngleIter(data,0,data.size-1)
  @inline override def reverseIterator: Iterator[Angle] = new ReverseAngleIter(data,data.size-1,0)

  @inline override def slice(from: Int, until: Int): Self = new AngleArrayBuffer(data.slice(from,until))
  @inline override def tail: Self = new AngleArrayBuffer(data.tail)
  @inline override def take (n: Int): Self = new AngleArrayBuffer(data.take(n))
  @inline override def drop (n: Int): Self = new AngleArrayBuffer(data.drop(n))

  @inline override def last: Angle = new Angle(data.last)
  @inline override def exists(p: (Angle)=>Boolean): Boolean = data.exists( d=> p(new Angle(d)))
  @inline override def count(p: (Angle)=>Boolean): Int = data.count( d=> p(new Angle(d)))
  @inline override def max: Angle = new Angle(data.max)
  @inline override def min: Angle = new Angle(data.min)

  override def toArray: SelfArray = new AngleArray(data.toArray)

  def toRadiansArray: Array[Double] = data.toArray
  def toDegreesArray: Array[Double] = toDoubleArray(DegreesInRadian)
}

//--- delta angle support (a memory saving optimization if we know diffs are bounded)

private[uom] class DeltaAngleIter (data: Seq[Float], first: Int, last: Int, ref: Angle) extends Iterator[Angle] {
  private var i = first
  def hasNext: Boolean = (i <= last)
  def next(): Angle = {
    val j = i
    if (j > last) throw new NoSuchElementException
    i += 1
    ref + new Angle(data(j))
  }
}

private[uom] class ReverseDeltaAngleIter (data: Seq[Float], first: Int, last: Int, ref: Angle) extends Iterator[Angle] {
  private var i = first
  def hasNext: Boolean = (i >= last)
  def next(): Angle = {
    val j = i
    if (j < last) throw new NoSuchElementException
    i -= 1
    ref + new Angle(data(j))
  }
}

/**
  * stores Angles as Float diff to a fixed ref value
  */
final class DeltaAngleArray protected[uom] (protected[uom] val data: Array[Float], val ref: Angle) {
  import scala.math.Ordering.Float.TotalOrdering


  def this(len: Int, ref: Angle) = this(new Array[Float](len), ref)

  def grow(newCapacity: Int): DeltaAngleArray = new DeltaAngleArray(ArrayUtils.grow(data,newCapacity),ref)

  @inline def delta(i: Int): Angle = new Angle(data(i))

  @inline def apply(i:Int): Angle = ref + new Angle(data(i))
  @inline def update(i:Int, a: Angle): Unit = data(i) = (a - ref).toRadians.toFloat
  @inline def foreach(f: (Angle)=>Unit): Unit = data.foreach( d=> f(ref + new Angle(d)))
  @inline def iterator: Iterator[Angle] = new DeltaAngleIter(data,0,data.size-1,ref)
  @inline def reverseIterator: Iterator[Angle] = new ReverseDeltaAngleIter(data,data.size-1,0,ref)

  @inline def slice(from: Int, until: Int): DeltaAngleArray = new DeltaAngleArray(data.slice(from,until),ref)
  @inline def tail: DeltaAngleArray = new DeltaAngleArray(data.tail,ref)
  @inline def take (n: Int): DeltaAngleArray = new DeltaAngleArray(data.take(n),ref)
  @inline def drop (n: Int): DeltaAngleArray = new DeltaAngleArray(data.drop(n),ref)

  // TODO - check usage - ref of destination is not changed
  @inline def copyToArray(other: DeltaAngleArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: DeltaAngleArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: DeltaAngleArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def copyFrom(other: DeltaAngleArray, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    if (ref != other.ref) throw new RuntimeException("cannot copy to delta array with different reference value")
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline def last: Angle = ref + new Angle(data.last)
  @inline def exists(p: (Angle)=>Boolean): Boolean = data.exists( d=> p(ref + new Angle(d)))
  @inline def count(p: (Angle)=>Boolean): Int = data.count( d=> p(ref + new Angle(d)))
  @inline def max: Angle = ref + new Angle(data.max)
  @inline def min: Angle = ref + new Angle(data.min)

  def toDegreesArray: Array[Double] = {
    val n = data.length
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Angle(data(i))).toDegrees; i += 1 }
    a
  }
  def toRadiansArray: Array[Double] = {
    val n = data.length
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Angle(data(i))).toRadians; i += 1 }
    a
  }

  def toBuffer: DeltaAngleArrayBuffer = new DeltaAngleArrayBuffer(ArrayBuffer.from(data),ref)
}

final class DeltaAngleArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Float], val ref: Angle) {
  import scala.math.Ordering.Float.TotalOrdering

  def this (initialSize: Int, ref: Angle) = this(new ArrayBuffer[Float](initialSize), ref)

  @inline def size = data.size

  @inline def += (v: Angle): DeltaAngleArrayBuffer = { data += (v - ref).toRadians.toFloat; this }
  @inline def append (vs: Angle*): Unit = vs.foreach( a=> data += (a - ref).toRadians.toFloat )

  @inline def delta(i: Int): Angle = new Angle(data(i))

  @inline def apply(i:Int): Angle = ref + new Angle(data(i))
  @inline def update(i:Int, a: Angle): Unit = data(i) = (a - ref).toRadians.toFloat
  @inline def foreach(f: (Angle)=>Unit): Unit = data.foreach( d=> f(ref + new Angle(d)))
  @inline def iterator: Iterator[Angle] = new DeltaAngleIter(data,0,data.size-1,ref)
  @inline def reverseIterator: Iterator[Angle] = new ReverseDeltaAngleIter(data,data.size-1,0,ref)

  @inline def slice(from: Int, until: Int): DeltaAngleArrayBuffer = new DeltaAngleArrayBuffer(data.slice(from,until),ref)
  @inline def tail: DeltaAngleArrayBuffer = new DeltaAngleArrayBuffer(data.tail,ref)
  @inline def take (n: Int): DeltaAngleArrayBuffer = new DeltaAngleArrayBuffer(data.take(n),ref)
  @inline def drop (n: Int): DeltaAngleArrayBuffer = new DeltaAngleArrayBuffer(data.drop(n),ref)

  @inline def copyToArray(other: DeltaAngleArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: DeltaAngleArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: DeltaAngleArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Angle = ref + new Angle(data.last)
  @inline def exists(p: (Angle)=>Boolean): Boolean = data.exists( d=> p(ref + new Angle(d)))
  @inline def count(p: (Angle)=>Boolean): Int = data.count( d=> p(ref + new Angle(d)))
  @inline def max: Angle = ref + new Angle(data.max)
  @inline def min: Angle = ref + new Angle(data.min)

  def toArray: DeltaAngleArray = new DeltaAngleArray(data.toArray,ref)

  def toDegreesArray: Array[Double] = {
    val n = data.size
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Angle(data(i))).toDegrees; i += 1 }
    a
  }
  def toRadiansArray: Array[Double] = {
    val n = data.size
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Angle(data(i))).toRadians; i += 1 }
    a
  }
}

class AngleStats extends SampleStats[Angle] with OnlineSampleStatsImplD {
  @inline def addSample (v: Angle): Unit = addSampleD(v.toRadians)
  @inline def mean: Angle = Radians(_mean)
  @inline def min: Angle = Radians(_min)
  @inline def max: Angle = Radians(_max)
  @inline def isMinimum (v: Angle): Boolean = v.toRadians <= _min
  @inline def isMaximum (v: Angle): Boolean = v.toRadians >= _max
}