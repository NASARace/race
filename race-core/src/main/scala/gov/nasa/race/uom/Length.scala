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

import scala.concurrent.duration.Duration
import Math._

import gov.nasa.race._
import gov.nasa.race.common.{OnlineSampleStatsImplD, SampleStats}
import gov.nasa.race.util.ArrayUtils

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

/**
  * length quantities
  * underlying unit is meters
  */
object Length {
  //--- constants
  final val MetersInNauticalMile = 1852.0
  final val MetersInUsMile = 1609.344
  final val MetersInFoot = 0.3048
  final val MetersInInch = 0.0254

  final val Length0 = Meters(0)
  final val MaxLength = Meters(Double.MaxValue)
  final val MinLength = Meters(Double.MinValue)
  final val UndefinedLength = Meters(Double.NaN)
  @inline def isDefined(x: Length): Boolean = !x.d.isNaN

  final implicit val εLength = Meters(1e-9)

  @inline def Abs(l: Length) = Meters(abs(l.d))

  //--- Length constructors (basis is meter)
  @inline def Kilometers(d: Double) = new Length(d*1000.0)
  @inline def Meters(d: Double) = new Length(d)
  @inline def Centimeters(d: Double) = new Length(d/100.0)
  @inline def Millimeters(d: Double) = new Length(d/1000.0)
  @inline def NauticalMiles(d: Double) = new Length(d * MetersInNauticalMile)
  @inline def UsMiles(d: Double) = new Length(d * MetersInUsMile)
  @inline def Feet(d: Double) = new Length(d * MetersInFoot)
  @inline def Inches(d: Double) = new Length(d * MetersInInch)

  implicit class LengthConstructor (val d: Double) extends AnyVal {
    @inline def kilometers = Kilometers(d)
    @inline def km = Kilometers(d)
    @inline def meters = Meters(d)
    @inline def m = Meters(d)
    @inline def centimeters = Centimeters(d)
    @inline def cm =  Centimeters(d)
    @inline def millimeters = Millimeters(d)
    @inline def mm = Millimeters(d)
    @inline def nauticalMiles = NauticalMiles(d)
    @inline def nm = NauticalMiles(d)
    @inline def usMiles = UsMiles(d)
    @inline def mi = UsMiles(d)
    @inline def feet = Feet(d)
    @inline def ft = Feet(d)
    @inline def inches = Inches(d)
    @inline def in = Inches(d)
  }

  //--- to support expressions with a leading unit-less numeric factor
  implicit class LengthDoubleFactor (val d: Double) extends AnyVal {
    @inline def * (x: Length) = new Length(x.d * d)
  }
  implicit class LengthIntFactor (val d: Int) extends AnyVal {
    @inline def * (x: Length) = new Length(x.d * d)
  }

  @inline final def meters2Feet(m: Double) = m / MetersInFoot
  @inline final def feet2Meters(f: Double) = f * MetersInFoot
}

import Length._


/**
  * basis is meters, ISO symbol is 'm'
  */
class Length protected[uom] (val d: Double) extends AnyVal
                                        with Ordered[Length] with MaybeUndefined {

  //--- Double converters
  @inline def toKilometers = d / 1000.0
  @inline def toMeters = d
  @inline def toCentimeters = d * 100.0
  @inline def toMillimeters = d * 1000.0
  @inline def toUsMiles = d / MetersInUsMile
  @inline def toFeet = d / MetersInFoot
  @inline def toInches = d / MetersInInch
  @inline def toNauticalMiles = d / MetersInNauticalMile

  @inline def toRoundedMeters: Long = d.round

  @inline def + (x: Length) = new Length(d + x.d)
  @inline def - (x: Length) = new Length(d - x.d)

  @inline def * (c: Double) = new Length(d * c)
  @inline def * (c: Length)(implicit r: LengthDisambiguator.type) = new Area(d * c.d)
  @inline def `²`(implicit r: LengthDisambiguator.type) = new Area(d * d)

  @inline def / (c: Double): Length = new Length(d / c)
  @inline def / (x: Length)(implicit r: LengthDisambiguator.type): Double = d / x.d

  @inline def / (t: Duration) = new Speed(d/t.toSeconds)

  @inline def ≈ (x: Length)(implicit εLength: Length) = Math.abs(d - x.d) <= εLength.d
  @inline def ~= (x: Length)(implicit εLength: Length) = Math.abs(d - x.d) <= εLength.d
  @inline def within (x: Length, distance: Length) = Math.abs(d - x.d) <= distance.d

  @inline def =:= (x: Length) = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Length) = d == x.d

  // we intentionally omit == since this is based on Double

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isDefined = !d.isNaN
  @inline override def isUndefined: Boolean = d.isNaN

  // comparison
  @inline override def < (x: Length) = d < x.d
  @inline override def <= (x: Length) = d <= x.d
  @inline override def > (x: Length) = d > x.d
  @inline override def >= (x: Length) = d >= x.d

  @inline def isPositive: Boolean = (d >= 0)
  @inline def abs: Length = new Length( Math.abs(d))
  @inline def neg: Length = new Length(-d)

  @inline override def compare (other: Length): Int = if (d > other.d) 1 else if (d < other.d) -1 else 0
  @inline override def compareTo (other: Length): Int = compare(other)

  override def toString = show   // calling this would cause allocation
  def show = s"${d}m"
  def showMeters = s"${d}m"
  def showFeet = s"${toFeet}ft"
  def showNauticalMiles = s"${toNauticalMiles}nm"
  def showNm = showNauticalMiles
  def showUsMiles = s"${toUsMiles}mi"
  def showKilometers = s"${toKilometers}km"

  def showRounded = f"${toMeters}%.0fm"
}

object LengthArray {
  def MetersArray (a: Array[Double]): LengthArray = new LengthArray(a.clone())
  def FeetArray (a: Array[Double]): LengthArray = new LengthArray(UOMDoubleArray.initData(a,MetersInFoot))
  def NauticalMilesArray (a: Array[Double]): LengthArray = new LengthArray(UOMDoubleArray.initData(a,MetersInNauticalMile))
  def UsMilesArray (a: Array[Double]): LengthArray = new LengthArray(UOMDoubleArray.initData(a,MetersInUsMile))
  def KilometersArray (a: Array[Double]): LengthArray = new LengthArray(UOMDoubleArray.initData(a,1000.0))
}


// we need to define these explicitly since we otherwise call a conversion function for each value

private[uom] class LengthIter (data: Seq[Double], first: Int, last: Int) extends Iterator[Length] {
  private var i = first
  def hasNext: Boolean = (i <= last)
  def next(): Length = {
    val j = i
    if (j > last) throw new NoSuchElementException
    i += 1
    new Length(data(j))
  }
}

private[uom] class ReverseLengthIter (data: Seq[Double], first: Int, last: Int) extends Iterator[Length] {
  private var i = first
  def hasNext: Boolean = (i >= last)
  def next(): Length = {
    val j = i
    if (j < last) throw new NoSuchElementException
    i -= 1
    new Length(data(j))
  }
}

/**
  * wrapper class for arrays of Lengths without per element allocation
  * TODO - needs more Array methods
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class LengthArray protected[uom] (protected[uom] val data: Array[Double]) extends UOMDoubleArray[Length] {
  import scala.math.Ordering.Double.TotalOrdering

  type Self = LengthArray
  type SelfBuffer = LengthArrayBuffer

  def this(len: Int) = this(new Array[Double](len))

  override def grow(newCapacity: Int): LengthArray = {
    val newData = new Array[Double](newCapacity)
    System.arraycopy(data,0,newData,0,data.length)
    new LengthArray(newData)
  }

  @inline override def copyFrom(other: Self, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline override def apply(i:Int): Length = new Length(data(i))
  @inline override def update(i:Int, v: Length): Unit = data(i) = v.toMeters
  @inline override def foreach(f: (Length)=>Unit): Unit = data.foreach( d=> f(new Length(d)))

  @inline override def iterator: Iterator[Length] = new LengthIter(data,0,data.length-1)
  @inline override def reverseIterator: Iterator[Length] = new ReverseLengthIter(data,data.length-1,0)

  @inline override def slice(from: Int, until: Int): Self = new LengthArray(data.slice(from,until))
  @inline override def tail: Self = new LengthArray(data.tail)
  @inline override def take (n: Int): Self = new LengthArray(data.take(n))
  @inline override def drop (n: Int): Self = new LengthArray(data.drop(n))

  @inline override def last: Length = Meters(data.last)
  @inline override def exists(p: (Length)=>Boolean): Boolean = data.exists( d=> p(new Length(d)))
  @inline override def count(p: (Length)=>Boolean): Int = data.count( d=> p(new Length(d)))
  @inline override def max: Length = new Length(data.max)
  @inline override def min: Length = new Length(data.min)

  override def toBuffer: SelfBuffer = LengthArrayBuffer.MetersArrayBuffer(data)

  def toMetersArray: Array[Double] = data.clone()
  def toFeetArray: Array[Double] = toDoubleArray(MetersInFoot)
  def toUsMilesArray: Array[Double] = toDoubleArray(MetersInUsMile)
  def toNauticalMilesArray: Array[Double] = toDoubleArray(MetersInNauticalMile)
  def toKilometersArray: Array[Double] = toDoubleArray(1000)
}

object LengthArrayBuffer {
  def MetersArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMDoubleArrayBuffer.initData(a))
  def FeetArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMDoubleArrayBuffer.initData(a,MetersInFoot))
  def NauticalMilesArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMDoubleArrayBuffer.initData(a,MetersInNauticalMile))
  def UsMilesArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMDoubleArrayBuffer.initData(a,MetersInUsMile))
  def KilometersArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMDoubleArrayBuffer.initData(a,1000.0))
}

final class LengthArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Double]) extends UOMDoubleArrayBuffer[Length] {
  import scala.math.Ordering.Double.TotalOrdering

  type Self = LengthArrayBuffer
  type SelfArray = LengthArray

  def this(initialSize: Int) = this(new ArrayBuffer[Double](initialSize))

  @inline override def += (v: Length): Self = { data += v.toMeters; this }
  @inline override def append (vs: Length*): Unit = vs.foreach( data += _.toMeters )

  @inline override def apply(i:Int): Length = new Length(data(i))
  @inline override def update(i:Int, v: Length): Unit = data(i) = v.toMeters
  @inline override def foreach(f: (Length)=>Unit): Unit = data.foreach( d=> f(new Length(d)))

  @inline override def iterator: Iterator[Length] = new LengthIter(data,0,data.size-1)
  @inline override def reverseIterator: Iterator[Length] = new ReverseLengthIter(data,data.size-1,0)

  @inline override def slice(from: Int, until: Int): Self = new LengthArrayBuffer(data.slice(from,until))
  @inline override def tail: Self = new LengthArrayBuffer(data.tail)
  @inline override def take (n: Int): Self = new LengthArrayBuffer(data.take(n))
  @inline override def drop (n: Int): Self = new LengthArrayBuffer(data.drop(n))

  @inline override def last: Length = new Length(data.last)
  @inline override def exists(p: (Length)=>Boolean): Boolean = data.exists( d=> p(new Length(d)))
  @inline override def count(p: (Length)=>Boolean): Int = data.count( d=> p(new Length(d)))
  @inline override def max: Length = new Length(data.max)
  @inline override def min: Length = new Length(data.min)

  override def toArray: SelfArray = new LengthArray(data.toArray)

  def toMetersArray: Array[Double] = data.toArray
  def toFeetArray: Array[Double] = toDoubleArray(MetersInFoot)
  def toUsMilesArray: Array[Double] = toDoubleArray(MetersInUsMile)
  def toNauticalMilesArray: Array[Double] = toDoubleArray(MetersInNauticalMile)
  def toKilometersArray: Array[Double] = toDoubleArray(1000)
}


//--- delta angle support (a memory saving optimization if we know diffs are bounded)

private[uom] class DeltaLengthIter (data: Seq[Float], first: Int, last: Int, ref: Length) extends Iterator[Length] {
  private var i = first
  def hasNext: Boolean = (i <= last)
  def next(): Length = {
    val j = i
    if (j > last) throw new NoSuchElementException
    i += 1
    ref + new Length(data(j))
  }
}

private[uom] class ReverseDeltaLengthIter (data: Seq[Float], first: Int, last: Int, ref: Length) extends Iterator[Length] {
  private var i = first
  def hasNext: Boolean = (i >= last)
  def next(): Length = {
    val j = i
    if (j < last) throw new NoSuchElementException
    i -= 1
    ref + new Length(data(j))
  }
}

/**
  * stores Lengths as Float diff to a fixed ref value
  */
final class DeltaLengthArray protected[uom] (protected[uom] val data: Array[Float], val ref: Length) {
  import scala.math.Ordering.Float.TotalOrdering

  def this(len: Int, ref: Length) = this(new Array[Float](len), ref)

  def grow(newCapacity: Int): DeltaLengthArray = new DeltaLengthArray(ArrayUtils.grow(data,newCapacity),ref)

  @inline def delta(i: Int): Length = new Length(data(i))

  @inline def apply(i:Int): Length = ref + new Length(data(i))
  @inline def update(i:Int, a: Length): Unit = data(i) = (a - ref).toMeters.toFloat
  @inline def foreach(f: (Length)=>Unit): Unit = data.foreach( d=> f(ref + new Length(d)))
  @inline def iterator: Iterator[Length] = new DeltaLengthIter(data,0,data.size-1,ref)
  @inline def reverseIterator: Iterator[Length] = new ReverseDeltaLengthIter(data,data.size-1,0,ref)

  @inline def slice(from: Int, until: Int): DeltaLengthArray = new DeltaLengthArray(data.slice(from,until),ref)
  @inline def tail: DeltaLengthArray = new DeltaLengthArray(data.tail,ref)
  @inline def take (n: Int): DeltaLengthArray = new DeltaLengthArray(data.take(n),ref)
  @inline def drop (n: Int): DeltaLengthArray = new DeltaLengthArray(data.drop(n),ref)

  // NOTE - ref of destination is not changed
  @inline def copyToArray(other: DeltaLengthArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: DeltaLengthArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: DeltaLengthArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def copyFrom(other: DeltaLengthArray, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    if (ref != other.ref) throw new RuntimeException("cannot copy to delta array with different reference value")
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline def last: Length = ref + new Length(data.last)
  @inline def exists(p: (Length)=>Boolean): Boolean = data.exists( d=> p(ref + new Length(d)))
  @inline def count(p: (Length)=>Boolean): Int = data.count( d=> p(ref + new Length(d)))
  @inline def max: Length = ref + new Length(data.max)
  @inline def min: Length = ref + new Length(data.min)

  def toMetersArray: Array[Double] = {
    val n = data.length
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Length(data(i))).toMeters; i += 1 }
    a
  }
  def toFeetArray: Array[Double] = {
    val n = data.length
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Length(data(i))).toFeet; i += 1 }
    a
  }

  def toBuffer: DeltaLengthArrayBuffer = new DeltaLengthArrayBuffer(ArrayBuffer.from(data),ref)
}

final class DeltaLengthArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Float], val ref: Length) {
  import scala.math.Ordering.Float.TotalOrdering

  def this (initialSize: Int, ref: Length) = this(new ArrayBuffer[Float](initialSize), ref)

  @inline def size = data.size

  @inline def += (v: Length): DeltaLengthArrayBuffer = { data += (v - ref).toMeters.toFloat; this }
  @inline def append (vs: Length*): Unit = vs.foreach( a=> data += (a - ref).toMeters.toFloat )

  @inline def delta(i: Int): Length = new Length(data(i))

  @inline def apply(i:Int): Length = ref + new Length(data(i))
  @inline def update(i:Int, a: Length): Unit = data(i) = (a - ref).toMeters.toFloat
  @inline def foreach(f: (Length)=>Unit): Unit = data.foreach( d=> f(ref + new Length(d)))
  @inline def iterator: Iterator[Length] = new DeltaLengthIter(data,0,data.size-1,ref)
  @inline def reverseIterator: Iterator[Length] = new ReverseDeltaLengthIter(data,data.size-1,0,ref)

  @inline def slice(from: Int, until: Int): DeltaLengthArrayBuffer = new DeltaLengthArrayBuffer(data.slice(from,until),ref)
  @inline def tail: DeltaLengthArrayBuffer = new DeltaLengthArrayBuffer(data.tail,ref)
  @inline def take (n: Int): DeltaLengthArrayBuffer = new DeltaLengthArrayBuffer(data.take(n),ref)
  @inline def drop (n: Int): DeltaLengthArrayBuffer = new DeltaLengthArrayBuffer(data.drop(n),ref)

  @inline def copyToArray(other: DeltaLengthArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: DeltaLengthArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: DeltaLengthArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Length = ref + new Length(data.last)
  @inline def exists(p: (Length)=>Boolean): Boolean = data.exists( d=> p(ref + new Length(d)))
  @inline def count(p: (Length)=>Boolean): Int = data.count( d=> p(ref + new Length(d)))
  @inline def max: Length = ref + new Length(data.max)
  @inline def min: Length = ref + new Length(data.min)

  def toArray: DeltaLengthArray = new DeltaLengthArray(data.toArray,ref)

  def toMetersArray: Array[Double] = {
    val n = data.size
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Length(data(i))).toMeters; i += 1 }
    a
  }
  def toFeetArray: Array[Double] = {
    val n = data.size
    val a = new Array[Double](n)
    var i=0
    while (i < n) { a(i) = (ref + new Length(data(i))).toFeet; i += 1 }
    a
  }
}

class LengthStats extends SampleStats[Length] with OnlineSampleStatsImplD {
  @inline def addSample (l: Length): Unit = addSampleD(l.toMeters)
  @inline def mean: Length = Meters(_mean)
  @inline def min: Length = Meters(_min)
  @inline def max: Length = Meters(_max)
  @inline def isMinimum (l: Length): Boolean = l.toMeters <= _min
  @inline def isMaximum (l: Length): Boolean = l.toMeters >= _max
}