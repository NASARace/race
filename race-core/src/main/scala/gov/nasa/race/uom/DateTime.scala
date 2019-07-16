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

import java.time.{Instant, LocalDateTime, ZoneId, ZoneOffset, ZonedDateTime}
import java.time.format.{DateTimeFormatter, TextStyle}
import java.util.{Calendar, Locale, TimeZone}

import gov.nasa.race._
import gov.nasa.race.util.{DateTimeUtils, SeqUtils}
import org.joda.time.chrono.ISOChronology
import org.joda.time.{DateTimeZone, MutableDateTime, ReadableDateTime, DateTime => JodaDateTime}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, FiniteDuration, MILLISECONDS}
import scala.util.Sorting

class DateException (msg: String) extends RuntimeException(msg)


/**
  * constructors for Date
  */
object DateTime {
  val UNDEFINED_DATE = Long.MinValue
  val isoChronoUTC = ISOChronology.getInstanceUTC

  @inline def apply(d: ReadableDateTime): DateTime = new DateTime(d.getMillis)
  @inline def apply(year: Int, month: Int, day: Int, hour: Int, minutes: Int, secs: Int, ms: Int): DateTime = {
    new DateTime(isoChronoUTC.getDateTimeMillis(year,month,day,hour,minutes,secs,ms))
  }
  @inline def apply(year: Int, month: Int, day: Int, hour: Int, minutes: Int, secs: Int, ms: Int, zone: DateTimeZone): DateTime = {
    val chrono = ISOChronology.getInstance(zone)
    new DateTime(chrono.getDateTimeMillis(year,month,day,hour,minutes,secs,ms))
  }

  def timeBetween (a: DateTime, b: DateTime): Time = {
    if (a.millis >= b.millis) new Time((a.millis - b.millis).toInt)
    else new Time((b.millis - a.millis).toInt)
  }

  def parseLocal(spec: String, dtf: DateTimeFormatter): DateTime = {
    val ta = dtf.parse(spec)
    val locDate = LocalDateTime.from(ta)
    val zonedDate = locDate.atZone(ZoneOffset.UTC)
    new DateTime(zonedDate.toEpochSecond * 1000)
  }

  val YMD_RE = """(?:(\d+)[/-](\d+)[/-](\d+)[ ,T]?)?(?:(\d\d):(\d\d):(\d\d)(?:\.(\d\d\d))?(?:[ ]?([+-]\d+|\w+))?)?""".r

  def parseYMDT(spec: String): DateTime = {
    spec match {
      case YMD_RE(year,month,day,hour,min,sec,milli,off) =>
        val tz = if (off == null) {
          TimeZone.getDefault
        } else {
          if (off.charAt(0) == '-' || off.charAt(0) == '+') {
            TimeZone.getTimeZone(ZoneId.ofOffset("UTC", ZoneOffset.ofHours(Integer.parseInt(off))))
          }else {
            TimeZone.getTimeZone(off)
          }
        }
        val calendar = Calendar.getInstance(tz)

        val yr = if (year == null) calendar.get(Calendar.YEAR) else Integer.parseInt(year)
        val mon = if (month == null) calendar.get(Calendar.MONTH) else Integer.parseInt(month)
        val d = if (day == null) calendar.get(Calendar.DAY_OF_MONTH) else Integer.parseInt(day)

        val h = if (hour == null) 0 else Integer.parseInt(hour)
        val m = if (min == null) 0 else Integer.parseInt(min)
        val s = if (sec == null) 0 else Integer.parseInt(sec)
        val ms = if (milli == null) 0 else Integer.parseInt(milli)

        calendar.clear
        calendar.setTimeZone(tz)
        calendar.set(yr,mon,d,h,m,s)

        new DateTime(calendar.getTimeInMillis + ms)
      case _ => throw new RuntimeException(s"invalid date spec: $spec")
    }
  }


  @inline def now: DateTime = new DateTime(System.currentTimeMillis)
  @inline def epochMillis(millis: Long) = new DateTime(millis)

  //--- constants

  val Date0 = new DateTime(0)
  val UndefinedDateTime = new DateTime(UNDEFINED_DATE)

  val LocalOffsetMillis: Long = ZonedDateTime.now.getOffset.getTotalSeconds * 1000
  val LocalOffsetHours: Int = ZonedDateTime.now.getOffset.getTotalSeconds / 3600
  val LocalZoneID: String = ZoneId.systemDefault.getDisplayName(TextStyle.SHORT,Locale.getDefault)
  val LocalTimeZone: TimeZone = TimeZone.getTimeZone(LocalZoneID)

  val GMTTimeZone: TimeZone = TimeZone.getTimeZone("GMT")
  val GMTZoneID: ZoneId = GMTTimeZone.toZoneId
}
import DateTime._

/**
  * value class representing absolute time value based on Unix epoch time (ms since 01/01/1970-00:00:00)
  * NOTE - this is stored as UTC epoch millis - we don't store a time zone or offset
  */
class DateTime protected[uom](val millis: Long) extends AnyVal with Ordered[DateTime] with Definable[DateTime] {

  def toMillis: Long = millis
  @inline def toLocalMillis: Long = (millis + LocalOffsetMillis)

  def getCalendarUTC: Calendar = {
    val cal = Calendar.getInstance(GMTTimeZone)
    cal.setTimeInMillis(millis)
    cal
  }

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

  // TODO use Calendar.getInstance(GMTZone).setTimeInMillis(millis).get(Calendar.XX) to extract

  def yearUTC: Int = getCalendarUTC.get(Calendar.YEAR)
  def monthUTC: Int = getCalendarUTC.get(Calendar.MONTH)
  def dayUTC: Int = getCalendarUTC.get(Calendar.DAY_OF_MONTH)
  def getYMD: (Int,Int,Int) = {
    val cal = getCalendarUTC
    (cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
  }

  def year(zone: DateTimeZone): Int = ISOChronology.getInstance(zone).year.get(millis)
  def month(zone: DateTimeZone): Int = ISOChronology.getInstance(zone).monthOfYear.get(millis)
  def day(zone: DateTimeZone): Int = ISOChronology.getInstance(zone).dayOfMonth.get(millis)

  //--- these refer to UTC
  @inline def hour: Int = ((millis % Time.MillisInDay) / Time.MillisInHour).toInt
  @inline def minutes: Int = ((millis % Time.MillisInHour) / Time.MillisInMinute).toInt
  @inline def seconds: Int = ((millis % Time.MillisInMinute) / 1000).toInt
  @inline def milliseconds: Int = (millis % 1000).toInt

  def localHour: Int = (((millis + LocalOffsetMillis) % Time.MillisInDay) / Time.MillisInHour).toInt

  def timeOfDay: Time = new Time((millis % Time.MillisInDay).toInt)
  def localTimeOfDay: Time = new Time(((millis + LocalOffsetMillis) % Time.MillisInDay).toInt)

  // unfortunately we can't overload '-' because of erasure
  @inline def timeUntil(d: DateTime): Time = new Time((d.millis - millis).toInt)
  def :-> (d: DateTime): Time = timeUntil(d)

  @inline def timeSince(d: DateTime): Time = new Time((millis - d.millis).toInt)
  def <-: (d: DateTime): Time = timeSince(d)

  def + (t: Time): DateTime = new DateTime(millis + t.millis)
  def - (t: Time): DateTime = new DateTime(millis - t.millis)

  def + (dur: Duration): DateTime = new DateTime(millis + dur.toMillis)
  def - (dur: Duration): DateTime = new DateTime(millis - dur.toMillis)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isUndefined: Boolean = millis == UNDEFINED_DATE
  @inline def isDefined = millis != UNDEFINED_DATE

  //--- comparison (TODO - handle undefined values)
  @inline override def < (o: DateTime): Boolean = millis < o.millis
  @inline override def <= (o: DateTime): Boolean = millis <= o.millis
  @inline override def > (o: DateTime): Boolean = millis > o.millis
  @inline override def >= (o: DateTime): Boolean = millis >= o.millis
  def compare (other: DateTime): Int = millis.compare(other.millis)

  override def toString: String = toYMDTString
  def showTTime: String = if (millis != UNDEFINED_DATE) DateTimeUtils.toISODateString(millis) else "<undefined>"
  def showDate: String = if (millis != UNDEFINED_DATE) DateTimeUtils.toISODateString(millis) else "<undefined>"
  def showHHMMSSZ: String = {
    if (millis != UNDEFINED_DATE) {
      f"$hour%02d:$minutes%02d:$seconds%02d Z"
    } else "<undefined>"
  }

  def toString(fmtSpec: String): String = {
    val fmt = DateTimeFormatter.ofPattern(fmtSpec)
    fmt.format(Instant.ofEpochMilli(millis).atZone(GMTZoneID))
  }

  def toYMDTString: String = {
    val (year,month,day) = getYMD
    f"$year%4d/$month%02d/$day%02dT$hour%02d:$minutes%02d:$seconds%02d.$milliseconds%03d Z"
  }

  def toTString: String = {
    f"$hour%02d:$minutes%02d:$seconds%02d.$milliseconds%03d"
  }

  def toHMSString: String = {
    f"$hour%02d:$minutes%02d:$seconds%02d"
  }

  //.. and possibly more formatters
}


/**
  * wrapper class for arrays of Date vals without per element allocation
  *
  * note that Array[T] is final hence we cannot extend it
  */
final class DateArray protected[uom] (protected[uom] val data: Array[Long]) {

  class Iter extends Iterator[DateTime] {
    private var i = 0
    def hasNext: Boolean = i < data.length
    def next: DateTime = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new DateTime(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[DateTime] {
    private var i = data.length-1
    def hasNext: Boolean = i >= 0
    def next: DateTime = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new DateTime(data(j)) // should not allocate
    }
  }

  def this(len: Int) = this(new Array[Long](len))

  def length: Int = data.length
  override def clone: DateArray = new DateArray(data.clone)

  def grow(newCapacity: Int): DateArray = {
    val newData = new Array[Long](newCapacity)
    System.arraycopy(data,0,newData,0,data.length)
    new DateArray(newData)
  }

  def copyFrom(other: DateArray, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline def apply(i: Int) = new DateTime(data(i))
  @inline def update(i:Int, v: DateTime): Unit = data(i) = v.millis

  @inline def iterator: Iterator[DateTime] = new Iter
  @inline def reverseIterator: Iterator[DateTime] = new ReverseIter

  def foreach(f: (DateTime)=>Unit): Unit = {
    var i = 0
    while (i < data.length) {
      f(new DateTime(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): DateArray = new DateArray(data.slice(from,until))
  @inline def tail: DateArray = new DateArray(data.tail)
  @inline def take (n: Int): DateArray = new DateArray(data.take(n))
  @inline def drop (n: Int): DateArray = new DateArray(data.drop(n))

  def toMillisecondArray: Array[Long] = data.clone

  def sort: Unit = Sorting.quickSort(data)

  def toBuffer: DateArrayBuffer = new DateArrayBuffer(ArrayBuffer.from(data))

  //... and more to follow
}

/**
  * mutable buffer for Date values
  */
final class DateArrayBuffer protected[uom] (protected[uom] val data: ArrayBuffer[Long]) {

  class Iter extends Iterator[DateTime] {
    private var i = 0
    def hasNext: Boolean = i < data.size
    def next: DateTime = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new DateTime(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[DateTime] {
    private var i = data.size-1
    def hasNext: Boolean = i >= 0
    def next: DateTime = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new DateTime(data(j)) // should not allocate
    }
  }

  def this(capacity: Int) = this(new ArrayBuffer[Long](capacity))

  @inline def size: Int = data.size
  @inline def isEmpty: Boolean = data.isEmpty
  @inline def nonEmpty: Boolean = data.nonEmpty
  override def clone: DateArrayBuffer = new DateArrayBuffer(data.clone)

  @inline def += (v: DateTime): Unit = { data += v.millis }
  @inline def append (vs: DateTime*): Unit = vs.foreach( data += _.millis )

  @inline def apply(i:Int): DateTime = new DateTime(data(i))
  @inline def update(i:Int, v: DateTime): Unit = {
    if (i < 0 || i >= data.size) throw new IndexOutOfBoundsException(i.toString)
    data(i) = v.millis
  }

  @inline def iterator: Iterator[DateTime] = new Iter
  @inline def reverseIterator: Iterator[DateTime] = new ReverseIter

  def foreach(f: (DateTime)=>Unit): Unit = {
    var i = 0
    while (i < data.size) {
      f(new DateTime(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): DateArrayBuffer = new DateArrayBuffer(data.slice(from,until))
  @inline def take (n: Int): DateArrayBuffer = new DateArrayBuffer(data.take(n))
  @inline def drop (n: Int): DateArrayBuffer = new DateArrayBuffer(data.drop(n))

  @inline def head: DateTime = new DateTime(data.head)
  @inline def tail: DateArrayBuffer = new DateArrayBuffer(data.tail)
  @inline def last: DateTime = new DateTime(data.last)

  def sort: Unit = SeqUtils.quickSort(data)

  def toArray: DateArray = new DateArray(data.toArray)

  //... and more to follow
}

