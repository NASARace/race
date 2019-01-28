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

import scala.collection.mutable.ArrayBuffer

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
class Length protected[uom] (val d: Double) extends AnyVal {

  //--- Double converters
  @inline def toKilometers = d / 1000.0
  @inline def toMeters = d
  @inline def toCentimeters = d * 100.0
  @inline def toMillimeters = d * 1000.0
  @inline def toUsMiles = d / MetersInUsMile
  @inline def toFeet = d / MetersInFoot
  @inline def toInches = d / MetersInInch
  @inline def toNauticalMiles = d / MetersInNauticalMile

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

  @inline def < (x: Length) = d < x.d
  @inline def <= (x: Length) = d <= x.d
  @inline def > (x: Length) = d > x.d
  @inline def >= (x: Length) = d >= x.d
  @inline def =:= (x: Length) = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Length) = d == x.d

  // we intentionally omit == since this is based on Double

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline def isUndefined = d.isNaN
  @inline def isDefined = !d.isNaN
  @inline def orElse(fallback: Length) = if (isDefined) this else fallback

  @inline def compare (other: Length): Int = d compare other.d

  override def toString = show   // calling this would cause allocation
  def show = s"${d}m"
  def showMeters = s"${d}m"
  def showFeet = s"${toFeet}ft"
  def showNauticalMiles = s"${toNauticalMiles}nm"
  def showNm = showNauticalMiles
  def showUsMiles = s"${toUsMiles}mi"
  def showKilometers = s"${toKilometers}km"
}

object LengthArray {
  def MetersArray (a: Array[Double]): LengthArray = new LengthArray(a.clone())
  def FeetArray (a: Array[Double]): LengthArray = new LengthArray(UOMArray.initData(a,MetersInFoot))
  def NauticalMilesArray (a: Array[Double]): LengthArray = new LengthArray(UOMArray.initData(a,MetersInNauticalMile))
  def UsMilesArray (a: Array[Double]): LengthArray = new LengthArray(UOMArray.initData(a,MetersInUsMile))
  def KilometersArray (a: Array[Double]): LengthArray = new LengthArray(UOMArray.initData(a,1000.0))
}

/**
  * wrapper class for arrays of Angles without per element allocation
  * TODO - needs more Array methods
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class LengthArray protected[uom] (protected[uom] val data: Array[Double]) extends UOMArray {

  def this(len: Int) = this(new Array[Double](len))

  @inline def apply(i:Int): Length = Meters(data(i))
  @inline def update(i:Int, v: Length): Unit = data(i) = v.toMeters
  @inline def foreach(f: (Length)=>Unit): Unit = data.foreach( d=> f(Meters(d)))

  @inline def slice(from: Int, until: Int): LengthArray = new LengthArray(data.slice(from,until))
  @inline def tail: LengthArray = new LengthArray(data.tail)
  @inline def take (n: Int): LengthArray = new LengthArray(data.take(n))
  @inline def drop (n: Int): LengthArray = new LengthArray(data.drop(n))

  @inline def copyToArray(other: LengthArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: LengthArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: LengthArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Length = Meters(data.last)
  @inline def exists(p: (Length)=>Boolean): Boolean = data.exists( d=> p(Meters(d)))
  @inline def count(p: (Length)=>Boolean): Int = data.count( d=> p(Meters(d)))
  @inline def max: Length = Meters(data.max)
  @inline def min: Length = Meters(data.min)

  def toBuffer: LengthArrayBuffer = LengthArrayBuffer.MetersArrayBuffer(data)
  def toMetersArray: Array[Double] = data.clone()
  def toFeetArray: Array[Double] = toDoubleArray(MetersInFoot)
  def toUsMilesArray: Array[Double] = toDoubleArray(MetersInUsMile)
  def toNauticalMilesArray: Array[Double] = toDoubleArray(MetersInNauticalMile)
  def toKilometersArray: Array[Double] = toDoubleArray(1000)
}

object LengthArrayBuffer {
  def MetersArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMArrayBuffer.initData(a))
  def FeetArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMArrayBuffer.initData(a,MetersInFoot))
  def NauticalMilesArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMArrayBuffer.initData(a,MetersInNauticalMile))
  def UsMilesArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMArrayBuffer.initData(a,MetersInUsMile))
  def KilometersArrayBuffer (a: Seq[Double]): LengthArrayBuffer = new LengthArrayBuffer(UOMArrayBuffer.initData(a,1000.0))
}

final class LengthArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Double]) extends UOMArrayBuffer {
  private class LengthIterator (it: Iterator[Double]) extends Iterator[Length] {
    @inline def hasNext: Boolean = it.hasNext
    @inline def next: Length = Meters(it.next)
  }

  def this(initialSize: Int) = this(new ArrayBuffer[Double](initialSize))

  @inline def += (v: Length): LengthArrayBuffer = { data += v.toMeters; this }
  @inline def append (vs: Length*): Unit = vs.foreach( data += _.toMeters )

  @inline def apply(i:Int): Length = Meters(data(i))
  @inline def update(i:Int, v: Length): Unit = data(i) = v.toMeters
  @inline def foreach(f: (Length)=>Unit): Unit = data.foreach( d=> f(Meters(d)))
  @inline def iterator: Iterator[Length] = new LengthIterator(data.iterator)
  @inline def reverseIterator: Iterator[Length] = new LengthIterator(data.reverseIterator)

  @inline def slice(from: Int, until: Int): LengthArrayBuffer = new LengthArrayBuffer(data.slice(from,until))
  @inline def tail: LengthArrayBuffer = new LengthArrayBuffer(data.tail)
  @inline def take (n: Int): LengthArrayBuffer = new LengthArrayBuffer(data.take(n))
  @inline def drop (n: Int): LengthArrayBuffer = new LengthArrayBuffer(data.drop(n))

  @inline def copyToArray(other: LengthArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: LengthArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: LengthArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Length = Meters(data.last)
  @inline def exists(p: (Length)=>Boolean): Boolean = data.exists( d=> p(Meters(d)))
  @inline def count(p: (Length)=>Boolean): Int = data.count( d=> p(Meters(d)))
  @inline def max: Length = Meters(data.max)
  @inline def min: Length = Meters(data.min)

  def toArray: LengthArray = new LengthArray(data.toArray)
  def toMetersArray: Array[Double] = data.toArray
  def toFeetArray: Array[Double] = toDoubleArray(MetersInFoot)
  def toUsMilesArray: Array[Double] = toDoubleArray(MetersInUsMile)
  def toNauticalMilesArray: Array[Double] = toDoubleArray(MetersInNauticalMile)
  def toKilometersArray: Array[Double] = toDoubleArray(1000)
}