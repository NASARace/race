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

import gov.nasa.race.util.{DateTimeUtils, SeqUtils}
import org.joda.time.chrono.ISOChronology
import org.joda.time.{DateTimeZone, MutableDateTime, ReadableDateTime, DateTime => JodaDateTime}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Sorting

class DateException (msg: String) extends RuntimeException(msg)


/**
  * constructors for Date
  */
object Date {
  val UNDEFINED_DATE = Long.MinValue
  val isoChronoUTC = ISOChronology.getInstanceUTC

  @inline def apply(d: ReadableDateTime): Date = new Date(d.getMillis)
  @inline def apply(year: Int, month: Int, day: Int, hour: Int, minutes: Int, secs: Int, ms: Int): Date = {
    new Date(isoChronoUTC.getDateTimeMillis(year,month,day,hour,minutes,secs,ms))
  }
  @inline def apply(year: Int, month: Int, day: Int, hour: Int, minutes: Int, secs: Int, ms: Int, zone: DateTimeZone): Date = {
    val chrono = ISOChronology.getInstance(zone)
    new Date(chrono.getDateTimeMillis(year,month,day,hour,minutes,secs,ms))
  }
  @inline def Now: Date = new Date(System.currentTimeMillis)

  val Date0 = new Date(0)
  val UndefinedDate = new Date(UNDEFINED_DATE)
}
import Date._

/**
  * value class representing absolute time value based on Unix epoch time (ms since 01/01/1970-00:00:00)
  *
  * NOTE - since this just reduces to a single Long we can't store the time zone, hence clients have to make sure
  * not to mix DateTime values from different zones
  */
class Date protected[uom](val millis: Long) extends AnyVal {

  def toMillis: Long = millis

  def toDateTime: JodaDateTime = {
    if (millis != UNDEFINED_DATE) new JodaDateTime(millis)
    else throw new DateException("undefined Date value")
  }
  def toFiniteDuration: FiniteDuration = {
    if (millis != UNDEFINED_DATE) FiniteDuration(millis,MILLISECONDS)
    else throw new DateException("undefined Date value")
  }

  def setDateTime(d: MutableDateTime): Unit = {
    if (millis != UNDEFINED_DATE) d.setMillis(millis)
    else throw new DateException("undefined Date value")
  }

  def yearUTC: Int = isoChronoUTC.year.get(millis)
  def monthUTC: Int = isoChronoUTC.monthOfYear.get(millis)
  def dayUTC: Int = isoChronoUTC.dayOfMonth.get(millis)

  def year(zone: DateTimeZone): Int = ISOChronology.getInstance(zone).year.get(millis)
  def month(zone: DateTimeZone): Int = ISOChronology.getInstance(zone).monthOfYear.get(millis)
  def day(zone: DateTimeZone): Int = ISOChronology.getInstance(zone).dayOfMonth.get(millis)

  @inline def hour: Int = ((millis % Time.MillisInDay) / Time.MillisInHour).toInt
  @inline def minutes: Int = ((millis % Time.MillisInHour) / Time.MillisInMinute).toInt
  @inline def seconds: Int = ((millis % Time.MillisInMinute) / 1000).toInt
  @inline def milliseconds: Int = (millis % 1000).toInt


  // unfortunately we can't overload '-' because of erasure
  def timeTo (d: Date): Time = new Time(millis - d.millis)

  def + (t: Time): Date = new Date(millis + t.millis)
  def - (t: Time): Date = new Date(millis - t.millis)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline def isUndefined: Boolean = millis == UNDEFINED_DATE
  @inline def isDefined = millis != UNDEFINED_DATE
  @inline def orElse(fallback: Date): Date = if (isDefined) this else fallback

  //--- comparison (TODO - handle undefined values)
  @inline def < (o: Date): Boolean = millis < o.millis
  @inline def <= (o: Date): Boolean = millis <= o.millis
  @inline def > (o: Date): Boolean = millis > o.millis
  @inline def >= (o: Date): Boolean = millis >= o.millis

  override def toString: String = showDate
  def showTTime: String = if (millis != UNDEFINED_DATE) DateTimeUtils.toISODateString(millis) else "<undefined>"
  def showDate: String = if (millis != UNDEFINED_DATE) DateTimeUtils.toISODateString(millis) else "<undefined>"
  //.. and possibly more formatters
}


/**
  * wrapper class for arrays of Date vals without per element allocation
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class DateArray protected[uom] (protected[uom] val data: Array[Long]) {

  class Iter extends Iterator[Date] {
    private var i = 0
    def hasNext: Boolean = i < data.length
    def next: Date = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new Date(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[Date] {
    private var i = data.length-1
    def hasNext: Boolean = i >= 0
    def next: Date = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new Date(data(j)) // should not allocate
    }
  }

  def this(len: Int) = this(new Array[Long](len))

  def length: Int = data.length
  override def clone: TimeArray = new TimeArray(data.clone)

  @inline def apply(i: Int) = new Date(data(i))
  @inline def update(i:Int, v: Date): Unit = data(i) = v.millis

  @inline def iterator: Iterator[Date] = new Iter
  @inline def reverseIterator: Iterator[Date] = new ReverseIter

  def foreach(f: (Date)=>Unit): Unit = {
    var i = 0
    while (i < data.length) {
      f(new Date(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): DateArray = new DateArray(data.slice(from,until))
  @inline def tail: DateArray = new DateArray(data.tail)
  @inline def take (n: Int): DateArray = new DateArray(data.take(n))
  @inline def drop (n: Int): DateArray = new DateArray(data.drop(n))

  def toMillisecondArray: Array[Long] = data.clone

  def sort: Unit = Sorting.quickSort(data)

  def toBuffer: DateArrayBuffer = new DateArrayBuffer(ArrayBuffer[Long](data:_*))

  //... and more to follow
}

/**
  * mutable buffer for Date values
  */
final class DateArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Long]) {

  class Iter extends Iterator[Date] {
    private var i = 0
    def hasNext: Boolean = i < data.size
    def next: Date = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new Date(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[Date] {
    private var i = data.size-1
    def hasNext: Boolean = i >= 0
    def next: Date = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new Date(data(j)) // should not allocate
    }
  }

  def this(capacity: Int) = this(new ArrayBuffer[Long](capacity))

  @inline def size: Int = data.size
  @inline def isEmpty: Boolean = data.isEmpty
  @inline def nonEmpty: Boolean = data.nonEmpty
  override def clone: DateArrayBuffer = new DateArrayBuffer(data.clone)

  @inline def += (v: Date): DateArrayBuffer = { data += v.millis; this }
  @inline def append (vs: Date*): Unit = vs.foreach( data += _.millis )

  @inline def apply(i:Int): Date = new Date(data(i))
  @inline def update(i:Int, v: Date): Unit = data(i) = v.millis

  @inline def iterator: Iterator[Date] = new Iter
  @inline def reverseIterator: Iterator[Date] = new ReverseIter

  def foreach(f: (Date)=>Unit): Unit = {
    var i = 0
    while (i < data.size) {
      f(new Date(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): DateArrayBuffer = new DateArrayBuffer(data.slice(from,until))
  @inline def take (n: Int): DateArrayBuffer = new DateArrayBuffer(data.take(n))
  @inline def drop (n: Int): DateArrayBuffer = new DateArrayBuffer(data.drop(n))

  @inline def head: Date = new Date(data.head)
  @inline def tail: DateArrayBuffer = new DateArrayBuffer(data.tail)
  @inline def last: Date = new Date(data.last)

  def sort: Unit = SeqUtils.quickSort(data)

  def toArray: DateArray = new DateArray(data.toArray)

  //... and more to follow
}

