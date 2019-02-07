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
package gov.nasa.race.uom

import gov.nasa.race.util.DateTimeUtils
import scala.collection.mutable.ArrayBuffer

class TimeException (msg: String) extends RuntimeException(msg)

/**
  * Value class based time abstraction for relative time (duration)
  */
object Time {
  final val UNDEFINED_TIME = Int.MinValue
  final val MillisInMinute: Int = 1000 * 60
  final val MillisInHour: Int = MillisInMinute * 60
  final val MillisInDay: Int = MillisInHour * 24

  //--- unit constructors
  @inline def Milliseconds(l: Int): Time = new Time(l)
  @inline def Seconds(s: Int): Time = new Time(s*1000)
  @inline def Minutes(m: Int): Time = new Time(m * MillisInMinute)
  @inline def Hours(h: Int): Time = new Time(h * MillisInHour)
  @inline def Days(d: Int): Time = new Time(d * MillisInDay)
  @inline def HMS(h: Int, m: Int, s: Int): Time = new Time(h * MillisInHour + m * MillisInMinute + s * 1000)
  @inline def HMSms(h: Int, m: Int, s: Int, ms: Int): Time = {
    new Time(h * MillisInHour + m * MillisInMinute + s * 1000 + ms)
  }
  @inline def HM_S(h: Int, m: Int, s: Double): Time = {
    new Time(h * MillisInHour + m * MillisInMinute + (s * 1000).toInt )
  }

  def unapply(t: Time): Option[(Int,Int,Int,Int)] = {
    val millis = t.millis
    if (millis != UNDEFINED_TIME) {
      val s = (millis / 1000) % 60
      val m = (millis / 60000) % 60
      val h = millis / 3600000
      val ms = millis % 1000
      Some((h,m,s,ms))
    } else None
  }

  final val Time0 = new Time(0)
  final val UndefinedTime = new Time(UNDEFINED_TIME)
}
import Time._

/**
  * relative time value (duration) based on milliseconds
  * the underlying type is Int, i.e. durations are limited to 24 days (=596h)
  *
  * note this cannot be directly converted into Date, but we can add to dates or extract time values from them
  */
class Time protected[uom] (val millis: Int) extends AnyVal {

  //--- converters
  def toMillis: Int = millis
  def toSeconds: Double = if (millis != UNDEFINED_TIME) millis / 1000.0 else Double.NaN
  def toHours: Double = if (millis != UNDEFINED_TIME) millis / MillisInHour else Double.NaN
  def toDays: Double = if (millis != UNDEFINED_TIME) millis / MillisInDay else Double.NaN
  def toHMSms: (Int,Int,Int,Int) = {
    val s = (millis / 1000) % 60
    val m = (millis / 60000) % 60
    val h = millis / 3600000
    val ms = millis % 1000
    (h,m,s,ms)
  }

  // we don't try to be symmetric with Date - it seems non-intuitive to add a Date to a Time
  def + (t: Time): Time = new Time(millis + t.millis)
  def - (t: Time): Time = new Time(millis - t.millis)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline def isUndefined: Boolean = millis == UNDEFINED_TIME
  @inline def isDefined = millis != UNDEFINED_TIME
  @inline def orElse(fallback: Time): Time = if (isDefined) this else fallback

  //--- comparison (TODO - handle undefined values)
  @inline def < (o: Time): Boolean = millis < o.millis
  @inline def <= (o: Time): Boolean = millis <= o.millis
  @inline def > (o: Time): Boolean = millis > o.millis
  @inline def >= (o: Time): Boolean = millis >= o.millis

  override def toString: String = showMillis   // calling this would cause allocation
  def showMillis: String = if (millis != UNDEFINED_TIME) s"${millis}ms" else "<undefined>"
  def showHHMMSS: String = if (millis != UNDEFINED_TIME) DateTimeUtils.durationMillisToHMMSS(millis) else "<undefined>"
}


object TimeArray {
  def MillisecondArray (a: Array[Int]): TimeArray = new TimeArray(a.clone)
  //... and more
}

/**
  * wrapper class for arrays of Time vals without per element allocation
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class TimeArray protected[uom] (protected[uom] val data: Array[Int]) {

  class Iter extends Iterator[Time] {
    private var i = 0
    def hasNext: Boolean = i < data.length
    def next: Time = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new Time(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[Time] {
    private var i = data.length-1
    def hasNext: Boolean = i >= 0
    def next: Time = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new Time(data(j)) // should not allocate
    }
  }

  def this(len: Int) = this(new Array[Int](len))

  def grow(newCapacity: Int): TimeArray = {
    val newData = new Array[Int](newCapacity)
    System.arraycopy(data,0,newData,0,data.length)
    new TimeArray(newData)
  }

  def length: Int = data.length
  override def clone: TimeArray = new TimeArray(data.clone)

  @inline def apply(i: Int) = Milliseconds(data(i))
  @inline def update(i:Int, v: Time): Unit = data(i) = v.toMillis

  @inline def iterator: Iterator[Time] = new Iter
  @inline def reverseIterator: Iterator[Time] = new ReverseIter

  def foreach(f: (Time)=>Unit): Unit = {
    var i = 0
    while (i < data.length) {
      f(new Time(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): TimeArray = new TimeArray(data.slice(from,until))
  @inline def tail: TimeArray = new TimeArray(data.tail)
  @inline def take (n: Int): TimeArray = new TimeArray(data.take(n))
  @inline def drop (n: Int): TimeArray = new TimeArray(data.drop(n))

  def toMillisecondArray: Array[Int] = data.clone

  def toBuffer: TimeArrayBuffer = new TimeArrayBuffer(ArrayBuffer[Int](data:_*))

  //... and more to follow
}

final class TimeArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Int]) {

  class Iter extends Iterator[Time] {
    private var i = 0
    def hasNext: Boolean = i < data.size
    def next: Time = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new Time(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[Time] {
    private var i = data.size-1
    def hasNext: Boolean = i >= 0
    def next: Time = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new Time(data(j)) // should not allocate
    }
  }

  def this(capacity: Int) = this(new ArrayBuffer[Int](capacity))

  @inline def size: Int = data.size
  @inline def isEmpty: Boolean = data.isEmpty
  @inline def nonEmpty: Boolean = data.nonEmpty
  override def clone: TimeArrayBuffer = new TimeArrayBuffer(data.clone)

  @inline def += (v: Time): Unit = { data += v.millis }
  @inline def append (vs: Time*): Unit = vs.foreach( data += _.millis )

  @inline def apply(i:Int): Time = new Time(data(i))
  @inline def update(i:Int, v: Time): Unit = {
    if (i < 0 || i >= data.size) throw new IndexOutOfBoundsException(i)
    data(i) = v.millis
  }

  @inline def iterator: Iterator[Time] = new Iter
  @inline def reverseIterator: Iterator[Time] = new ReverseIter

  def foreach(f: (Time)=>Unit): Unit = {
    var i = 0
    while (i < data.size) {
      f(new Time(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): TimeArrayBuffer = new TimeArrayBuffer(data.slice(from,until))
  @inline def take (n: Int): TimeArrayBuffer = new TimeArrayBuffer(data.take(n))
  @inline def drop (n: Int): TimeArrayBuffer = new TimeArrayBuffer(data.drop(n))

  @inline def head: Time = new Time(data.head)
  @inline def tail: TimeArrayBuffer = new TimeArrayBuffer(data.tail)
  @inline def last: Time = new Time(data.last)

  def toArray: TimeArray = new TimeArray(data.toArray)

  //... and more to follow
}
