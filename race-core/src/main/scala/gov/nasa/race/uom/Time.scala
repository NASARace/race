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
import org.joda.time.{DateTime, MutableDateTime, ReadableDateTime}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class TimeException (msg: String) extends RuntimeException(msg)

/**
  * Value class based time abstraction
  * There are a ton of date and time abstractions in system/app libraries. This implementation is
  * an attempt to provide a unified (although limited) interface and automatic conversions
  */
object Time {
  final val UNDEFINED_MILLIS = Long.MinValue
  final val MillisInHour = 1000 * 60 * 60
  final val MillisInDay = MillisInHour * 24

  //--- unit constructors
  @inline def Milliseconds(l: Long): Time = new Time(l)
  @inline def Seconds(s: Long): Time = new Time(s*1000L)
  @inline def Dated(d: ReadableDateTime): Time = new Time(d.getMillis)

  @inline def Now: Time = new Time(System.currentTimeMillis)

  final val Time0 = new Time(0)
  final val UndefinedTime = new Time(UNDEFINED_MILLIS)

}
import Time._

/**
  * time value
  * basis is milliseconds
  * date conversion is based on epoch (1970-01-01T00:00:00Z)
  *
  * TODO - eval if we need different types for absolute (epoch) and relative (duration) time
  */
class Time protected[uom] (val millis: Long) extends AnyVal {

  //--- converters
  def toMillis: Long = millis
  def toSeconds: Double = if (millis != UNDEFINED_MILLIS) millis / 1000.0 else Double.NaN
  def toHours: Double = if (millis != UNDEFINED_MILLIS) millis / MillisInHour else Double.NaN
  def toDays: Double = if (millis != UNDEFINED_MILLIS) millis / MillisInDay else Double.NaN
  def toHMS: Option[(Int,Int,Int)] = {
    if (millis != UNDEFINED_MILLIS) {
      val s = ((millis / 1000) % 60).toInt
      val m = ((millis / 60000) % 60).toInt
      val h = (millis / 3600000).toInt
      Some((h,m,s))
    } else None
  }
  def toDateTime: DateTime = {
    if (millis != UNDEFINED_MILLIS) new DateTime(millis)
    else throw new TimeException("undefined Time value")
  }
  def toFiniteDuration: FiniteDuration = {
    if (millis != UNDEFINED_MILLIS) FiniteDuration(millis,MILLISECONDS)
    else throw new TimeException("undefined Time value")
  }

  def setDateTime(d: MutableDateTime): Unit = {
    if (millis != UNDEFINED_MILLIS) d.setMillis(millis)
    else throw new TimeException("undefined Time value")
  }

  def + (t: Time): Time = new Time(millis + t.millis)
  def - (t: Time): Time = new Time(millis - t.millis)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline def isUndefined: Boolean = millis == UNDEFINED_MILLIS
  @inline def isDefined = millis != UNDEFINED_MILLIS
  @inline def orElse(fallback: Length) = if (isDefined) this else fallback

  @inline def compare (other: Time): Int = millis compare other.millis

  override def toString = show   // calling this would cause allocation
  def show: String = if (millis != UNDEFINED_MILLIS) s"${millis}ms" else "<undefined>"
  def showMillis: String = if (millis != UNDEFINED_MILLIS) s"${millis}ms" else "<undefined>"
  def showDate: String = if (millis != UNDEFINED_MILLIS) DateTimeUtils.dateMillisToTTime(millis) else "<undefined>"
  def showDurationHHMMSS: String = if (millis != UNDEFINED_MILLIS) DateTimeUtils.durationMillisToHMMSS(millis) else "<undefined>"
}


object TimeArray {
  def MillisecondArray (a: Array[Long]): TimeArray = new TimeArray(a.clone)
  def DateArray (a: Array[ReadableDateTime]): TimeArray = new TimeArray(a.map(_.getMillis))
  //... and more
}

final class TimeIterator (it: Iterator[Long]) extends Iterator[Time] {
  @inline def hasNext: Boolean = it.hasNext
  @inline def next: Time = new Time(it.next)
}

/**
  * wrapper class for arrays of Time vals without per element allocation
  * TODO - needs more Array methods
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class TimeArray protected[uom] (protected[uom] val data: Array[Long]) {

  def this(len: Int) = this(new Array[Long](len))

  def length: Int = data.length
  override def clone: TimeArray = new TimeArray(data.clone)

  @inline def apply(i: Int) = Milliseconds(data(i))
  @inline def update(i:Int, v: Time): Unit = data(i) = v.toMillis

  @inline def iterator: Iterator[Time] = new TimeIterator(data.iterator)
  @inline def reverseIterator: Iterator[Time] = new TimeIterator(data.reverseIterator)
  @inline def foreach(f: (Time)=>Unit): Unit = data.foreach( d=> f(Milliseconds(d)))

  @inline def slice(from: Int, until: Int): TimeArray = new TimeArray(data.slice(from,until))
  @inline def tail: TimeArray = new TimeArray(data.tail)
  @inline def take (n: Int): TimeArray = new TimeArray(data.take(n))
  @inline def drop (n: Int): TimeArray = new TimeArray(data.drop(n))

  def toMillisecondArray: Array[Long] = data.clone
  def toDateTimeArray: Array[DateTime] = data.map(new DateTime(_))

  def toBuffer: TimeArrayBuffer = new TimeArrayBuffer(ArrayBuffer[Long](data:_*))

  //... and more to follow
}

final class TimeArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Long]) {

  def this(capacity: Int) = this(new ArrayBuffer[Long](capacity))

  @inline def size: Int = data.size
  @inline def isEmpty: Boolean = data.isEmpty
  @inline def nonEmpty: Boolean = data.nonEmpty
  override def clone: TimeArrayBuffer = new TimeArrayBuffer(data.clone)

  @inline def += (v: Time): TimeArrayBuffer = { data += v.millis; this }
  @inline def append (vs: Time*): Unit = vs.foreach( data += _.millis )

  @inline def apply(i:Int): Time = new Time(data(i))
  @inline def update(i:Int, v: Time): Unit = data(i) = v.millis

  @inline def foreach(f: (Time)=>Unit): Unit = data.foreach( l=> f(new Time(l)))
  @inline def iterator: Iterator[Time] = new TimeIterator(data.iterator)
  @inline def reverseIterator: Iterator[Time] = new TimeIterator(data.reverseIterator)

  @inline def slice(from: Int, until: Int): TimeArrayBuffer = new TimeArrayBuffer(data.slice(from,until))
  @inline def take (n: Int): TimeArrayBuffer = new TimeArrayBuffer(data.take(n))
  @inline def drop (n: Int): TimeArrayBuffer = new TimeArrayBuffer(data.drop(n))

  @inline def head: Time = new Time(data.head)
  @inline def tail: TimeArrayBuffer = new TimeArrayBuffer(data.tail)
  @inline def last: Time = new Time(data.last)

  def toArray: TimeArray = new TimeArray(data.toArray)

  //... and more to follow
}