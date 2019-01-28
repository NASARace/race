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

import gov.nasa.race.common._

import scala.collection.mutable.ArrayBuffer
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
  final val TwoPi = π * 2.0
  final val DegreesInRadian = π / 180.0
  final val MinutesInRadian = DegreesInRadian * 60.0
  final val Angle0 = new Angle(0)
  final val Angle90 = new Angle(π_2)
  final val Angle180 = new Angle(π)
  final val Angle270 = new Angle(π3_2)
  final val UndefinedAngle = new Angle(Double.NaN)
  @inline def isDefined(x: Angle): Boolean  = !x.d.isNaN

  final implicit val εAngle = Degrees(1.0e-10)  // provide your own if application specific
  def fromVxVy (vx: Speed, vy: Speed) = Radians(normalizeRadians2Pi(Math.atan2(vx.d, vy.d)))

  //--- utilities
  @inline def normalizeRadians (d: Double) = d - π*2 * Math.floor((d + π) / (π*2)) // -π..π
  @inline def normalizeRadians2Pi (d: Double) = if (d<0) d % TwoPi + TwoPi else d % TwoPi  // 0..2π
  @inline def normalizeDegrees (d: Double) =  if (d < 0) d % 360 + 360 else d % 360 // 0..360
  @inline def normalizedDegreesToRadians (d: Double) = normalizeDegrees(d) * DegreesInRadian

  @inline def absDiff(a1: Angle, a2: Angle) = {
    val d1 = normalizeRadians2Pi(a1.d)
    val d2 = normalizeRadians2Pi(a2.d)
    val dd = abs(d1 - d2)
    if (dd > π) Radians(TwoPi - dd) else Radians(dd)
  }

  //--- trigonometrics functions
  // note we have to use upper case here because we otherwise get ambiguity errors when
  // importing both Angle._ and Math._ versions. This is a consequence of using a AnyVal Angle

  @inline def Sin(a:Angle) = sin(a.d)
  @inline def Sin2(a:Angle) = Sin(a)`²`
  @inline def Cos(a:Angle) = cos(a.d)
  @inline def Cos2(a:Angle) = Cos(a)`²`
  @inline def Tan(a:Angle) = tan(a.d)
  @inline def Tan2(a:Angle) = Tan(a)`²`


  //--- Angle constructors
  @inline def Degrees (d: Double) = new Angle(d * DegreesInRadian)
  @inline def Radians (d: Double) = new Angle(d)

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

class Angle protected[uom] (val d: Double) extends AnyVal {

  //---  Double converters
  @inline def toRadians: Double = d
  @inline def toDegrees: Double = d / DegreesInRadian
  @inline def toNormalizedDegrees: Double = normalizeDegrees(toDegrees)

  @inline def negative = new Angle(-d)

  //--- numeric and comparison operators
  @inline def + (x: Angle) = new Angle(d + x.d)
  @inline def - (x: Angle) = new Angle(d - x.d)

  @inline def * (x: Double) = new Angle(d * x)
  @inline def / (x: Double) = new Angle(d / x)
  @inline def / (x: Angle)(implicit r: AngleDisambiguator.type): Double = d / x.d

  @inline def ≈ (x: Angle)(implicit εAngle: Angle) = Math.abs(d - x.d) <= εAngle.d
  @inline def ~= (x: Angle)(implicit εAngle: Angle) = Math.abs(d - x.d) <= εAngle.d
  @inline def within (x: Angle, tolerance: Angle) = {
    Math.abs(normalizeRadians(normalizeRadians(d) - normalizeRadians(x.d))) <= tolerance.d
  }

  @inline def < (x: Angle) = d < x.d
  @inline def > (x: Angle) = d > x.d
  @inline def =:= (x: Angle) = d == x.d // use this if you really mean equality
  @inline def ≡ (x: Angle) = d == x.d
  // we intentionally omit ==, <=, >=

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline def isUndefined = d.isNaN
  @inline def isDefined = !d.isNaN
  @inline def orElse(fallback: Angle) = if (isDefined) this else fallback
  @inline def compare (other: Length): Int = d compare other.d

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
  def DegreesArray (a: Array[Double]): AngleArray = new AngleArray(UOMArray.initData(a,DegreesInRadian))
  def RadiansArray (a: Array[Double]): AngleArray = new AngleArray(a.clone())
}

/**
  * wrapper class for arrays of Angles without per element allocation
  * note that Array[T] is final hence we cannot extend it
  *
  * TODO - implement scala.collection.Seq[Angle]
  */
final class AngleArray protected[uom] (protected[uom] val data: Array[Double]) extends UOMArray {

  def this(len: Int) = this(new Array[Double](len))

  @inline def apply(i:Int): Angle = Radians(data(i))
  @inline def update(i:Int, a: Angle): Unit = data(i) = a.toRadians
  @inline def foreach(f: (Angle)=>Unit): Unit = data.foreach( d=> f(Radians(d)))

  @inline def slice(from: Int, until: Int): AngleArray = new AngleArray(data.slice(from,until))
  @inline def tail: AngleArray = new AngleArray(data.tail)
  @inline def take (n: Int): AngleArray = new AngleArray(data.take(n))
  @inline def drop (n: Int): AngleArray = new AngleArray(data.drop(n))

  @inline def copyToArray(other: AngleArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: AngleArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: AngleArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Angle = Radians(data.last)
  @inline def exists(p: (Angle)=>Boolean): Boolean = data.exists( d=> p(Radians(d)))
  @inline def count(p: (Angle)=>Boolean): Int = data.count( d=> p(Radians(d)))
  @inline def max: Angle = Radians(data.max)
  @inline def min: Angle = Radians(data.min)

  def toBuffer: AngleArrayBuffer = AngleArrayBuffer.RadiansArrayBuffer(data)
  def toDegreesArray: Array[Double] = toDoubleArray(DegreesInRadian)
  def toRadiansArray: Array[Double] = data.clone()
}

object AngleArrayBuffer {
  def DegreesArrayBuffer (as: Seq[Double]): AngleArrayBuffer = new AngleArrayBuffer(UOMArrayBuffer.initData(as,DegreesInRadian))
  def RadiansArrayBuffer (as: Seq[Double]): AngleArrayBuffer = new AngleArrayBuffer(UOMArrayBuffer.initData(as))
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
final class AngleArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Double]) extends UOMArrayBuffer {

  private class AngleIterator (it: Iterator[Double]) extends Iterator[Angle] {
    @inline def hasNext: Boolean = it.hasNext
    @inline def next: Angle = Radians(it.next)
  }

  def this (initialSize: Int) = this(new ArrayBuffer[Double](initialSize))

  @inline def += (v: Angle): AngleArrayBuffer = { data += v.toRadians; this }
  @inline def append (vs: Angle*): Unit = vs.foreach( data += _.toRadians )

  @inline def apply(i:Int): Angle = Radians(data(i))
  @inline def update(i:Int, a: Angle): Unit = data(i) = a.toRadians
  @inline def foreach(f: (Angle)=>Unit): Unit = data.foreach( d=> f(Radians(d)))
  @inline def iterator: Iterator[Angle] = new AngleIterator(data.iterator)
  @inline def reverseIterator: Iterator[Angle] = new AngleIterator(data.reverseIterator)

  @inline def slice(from: Int, until: Int): AngleArrayBuffer = new AngleArrayBuffer(data.slice(from,until))
  @inline def tail: AngleArrayBuffer = new AngleArrayBuffer(data.tail)
  @inline def take (n: Int): AngleArrayBuffer = new AngleArrayBuffer(data.take(n))
  @inline def drop (n: Int): AngleArrayBuffer = new AngleArrayBuffer(data.drop(n))

  @inline def copyToArray(other: AngleArray): Unit = data.copyToArray(other.data)
  @inline def copyToArray(other: AngleArray, start: Int): Unit = data.copyToArray(other.data, start)
  @inline def copyToArray(other: AngleArray, start: Int, len: Int): Unit = data.copyToArray(other.data, start, len)

  @inline def last: Angle = Radians(data.last)
  @inline def exists(p: (Angle)=>Boolean): Boolean = data.exists( d=> p(Radians(d)))
  @inline def count(p: (Angle)=>Boolean): Int = data.count( d=> p(Radians(d)))
  @inline def max: Angle = Radians(data.max)
  @inline def min: Angle = Radians(data.min)

  def toArray: AngleArray = new AngleArray(data.toArray)
  def toRadiansArray: Array[Double] = data.toArray
  def toDegreesArray: Array[Double] = toDoubleArray(DegreesInRadian)
}