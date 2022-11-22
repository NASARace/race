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

import java.time.{Duration => JDuration}

import gov.nasa.race.MaybeUndefined
import gov.nasa.race.util.DateTimeUtils

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.language.implicitConversions

class TimeException (msg: String) extends RuntimeException(msg)

/**
  * Value class based time abstraction for relative time (duration)
  */
object Time {
  final val UNDEFINED_TIME = Long.MinValue

  final val MillisInMinute: Long = 1000 * 60
  final val MillisInHour: Long = MillisInMinute * 60
  final val MillisInDay: Long = MillisInHour * 24

  final val DayDuration = new Time(MillisInDay)

  //--- unit constructors
  @inline def Milliseconds(l: Long): Time = new Time(l)
  @inline def Milliseconds(d: Double): Time = new Time(d.round)
  @inline def Seconds(s: Long): Time = new Time(s*1000)
  @inline def Seconds(s: Double): Time = new Time((s*1000).round)
  @inline def Minutes(m: Long): Time = new Time(m * MillisInMinute)
  @inline def Minutes(m: Double): Time = new Time((m * MillisInMinute).round)
  @inline def Hours(h: Long): Time = new Time(h * MillisInHour)
  @inline def Hours(h: Double): Time = new Time((h * MillisInHour).round)
  @inline def Days(d: Long): Time = new Time(d * MillisInDay)
  @inline def Days(d: Double): Time = new Time((d * MillisInDay).round)
  @inline def HM(h: Long, m: Long): Time = new Time(h * MillisInHour + m * MillisInMinute)
  @inline def HMS(h: Long, m: Long, s: Long): Time = new Time(h * MillisInHour + m * MillisInMinute + s * 1000)
  @inline def HMSms(h: Long, m: Long, s: Long, ms: Long): Time = {
    new Time(h * MillisInHour + m * MillisInMinute + s * 1000 + ms)
  }
  @inline def HM_S(h: Int, m: Int, s: Double): Time = {
    new Time(h * MillisInHour + m * MillisInMinute + (s * 1000).toInt )
  }

  // this parses ISO 8601 (e.g. "PT24H")
  def parse(spec: CharSequence): Time = new Time( JDuration.parse(spec).toMillis.toInt)

  def parseHHmmss (spec: CharSequence): Time = {
    spec match {
      case DateTimeUtils.hhmmssRE(hh,mm,ss) => HMS(hh.toInt, mm.toInt, ss.toInt)
      case _ => throw new RuntimeException(s"not a valid HH:mm:ss time spec: $spec")
    }
  }

  def parseDuration (spec: CharSequence): Time = {
    spec match {
      case DateTimeUtils.hhmmssRE(hh,mm,ss) => HMS(hh.toInt, mm.toInt, ss.toInt)
      case DateTimeUtils.durationRE(n,tbase) => Milliseconds((n.toLong * DateTimeUtils.timeBaseToMillis(tbase)).toInt)
      case _ => new Time( JDuration.parse(spec).toMillis.toInt)  // fall back to ISO
    }
  }

  def unapply(t: Time): Option[(Long,Long,Long,Long)] = {
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
  final val MaxTimeOfDay = new Time(MillisInDay)
  final val UndefinedTime = new Time(UNDEFINED_TIME)

  @inline def min (a: Time, b: Time): Time = if (a.millis <= b.millis) a else b
  @inline def min (a: Time, b: Time, c: Time): Time = if (a.millis <= b.millis) {
    if (a.millis <= c.millis) a else c
  } else {
    if (b.millis <= c.millis) b else c
  }

  @inline def max (a: Time, b: Time): Time = if (a.millis >= b.millis) a else b
  @inline def max (a: Time, b: Time, c: Time): Time = if (a.millis >= b.millis) {
    if (a.millis >= c.millis) a else c
  } else {
    if (b.millis >= c.millis) b else c
  }

  implicit def toFiniteDuration (t: Time): FiniteDuration = t.toFiniteDuration

  implicit def fromFiniteDuration (d: FiniteDuration): Time = Milliseconds(d.toMillis)
}
import Time._

/**
  * relative time value (duration) based on milliseconds
  * the underlying type is Int, i.e. durations are limited to 24 days (=596h)
  *
  * note this cannot be directly converted into Date, but we can add to dates or extract time values from them
  */
class Time protected[uom] (val millis: Long) extends AnyVal
                                    with Ordered[Time] with MaybeUndefined {

  //--- converters
  def toMillis: Long = millis

  // those are fractional - round if you need integers
  def toSeconds: Double = if (millis != UNDEFINED_TIME) millis / 1000.0 else Double.NaN
  def toMinutes: Double = if (millis != UNDEFINED_TIME) millis / 60000.0 else Double.NaN
  def toHours: Double = if (millis != UNDEFINED_TIME) millis / MillisInHour.toDouble else Double.NaN
  def toDays: Double = if (millis != UNDEFINED_TIME) millis / MillisInDay.toDouble else Double.NaN

  def toFullDays: Long =  if (millis != UNDEFINED_TIME) millis / MillisInDay else -1  // note this only truncates

  def toHMSms: (Long,Long,Long,Long) = {
    val s = (millis / 1000) % 60
    val m = (millis / 60000) % 60
    val h = millis / 3600000
    val ms = millis % 1000
    (h,m,s,ms)
  }
  def toFiniteDuration: FiniteDuration = new FiniteDuration(millis, scala.concurrent.duration.MILLISECONDS)

  @inline def nonZero: Boolean = millis > 0

  // we don't try to be symmetric with Date - it seems non-intuitive to add a Date to a Time
  @inline def + (t: Time): Time = new Time(millis + t.millis)
  @inline def - (t: Time): Time = new Time(millis - t.millis)
  @inline def / (t: Time): Double = millis.toDouble / t.millis

  // we can't overload the normal / operator because of type erasure
  @inline def / (d: Double): Time = new Time((millis / d).round.toLong)
  @inline def * (d: Double): Time = new Time((millis * d).round.toLong)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isUndefined: Boolean = millis == UNDEFINED_TIME
  @inline override def isDefined: Boolean = millis != UNDEFINED_TIME

  //--- comparison (TODO - handle undefined values)
  @inline override def < (o: Time): Boolean = millis < o.millis
  @inline override def <= (o: Time): Boolean = millis <= o.millis
  @inline override def > (o: Time): Boolean = millis > o.millis
  @inline override def >= (o: Time): Boolean = millis >= o.millis
  @inline override def compare (other: Time): Int = if (millis > other.millis) 1 else if (millis < other.millis) -1 else 0
  @inline override def compareTo (other: Time): Int = compare(other)

  @inline def isPositive: Boolean = millis > 0
  @inline def isNegative: Boolean = millis < 0

  override def toString: String = showMillis   // calling this would cause allocation
  def showMillis: String = if (millis != UNDEFINED_TIME) s"${millis}ms" else "<undefined>"
  def showHHMMSS: String = if (millis != UNDEFINED_TIME) DateTimeUtils.durationMillisToHMMSS(millis) else "<undefined>"
}


object TimeArray {
  def MillisecondArray (a: Array[Long]): TimeArray = new TimeArray(a.clone)
  //... and more
}

/**
  * wrapper class for arrays of Time vals without per element allocation
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class TimeArray protected[uom] (protected[uom] val data: Array[Long]) {

  class Iter extends Iterator[Time] {
    private var i = 0
    def hasNext: Boolean = i < data.length
    def next(): Time = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new Time(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[Time] {
    private var i = data.length-1
    def hasNext: Boolean = i >= 0
    def next(): Time = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new Time(data(j)) // should not allocate
    }
  }

  def this(len: Int) = this(new Array[Long](len))

  def grow(newCapacity: Int): TimeArray = {
    val newData = new Array[Long](newCapacity)
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

  def toMillisecondArray: Array[Long] = data.clone

  def toBuffer: TimeArrayBuffer = new TimeArrayBuffer(ArrayBuffer.from(data))

  //... and more to follow
}

final class TimeArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Long]) {

  class Iter extends Iterator[Time] {
    private var i = 0
    def hasNext: Boolean = i < data.size
    def next(): Time = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new Time(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[Time] {
    private var i = data.size-1
    def hasNext: Boolean = i >= 0
    def next(): Time = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new Time(data(j)) // should not allocate
    }
  }

  def this(capacity: Int) = this(new ArrayBuffer[Long](capacity))

  @inline def size: Int = data.size
  @inline def isEmpty: Boolean = data.isEmpty
  @inline def nonEmpty: Boolean = data.nonEmpty
  override def clone: TimeArrayBuffer = new TimeArrayBuffer(data.clone)

  @inline def += (v: Time): Unit = { data += v.millis }
  @inline def append (vs: Time*): Unit = vs.foreach( data += _.millis )

  @inline def apply(i:Int): Time = new Time(data(i))
  @inline def update(i:Int, v: Time): Unit = {
    if (i < 0 || i >= data.size) throw new IndexOutOfBoundsException(i.toString)
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
