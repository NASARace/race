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

import java.time.format.{DateTimeFormatter, TextStyle}
import java.time.temporal.ChronoField
import java.time._
import java.util.Locale

import gov.nasa.race._
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.util.SeqUtils

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, FiniteDuration, MILLISECONDS}
import scala.util.Sorting

class DateException (msg: String) extends RuntimeException(msg)


/**
  * constructors for Date
  */
object DateTime {
  val UNDEFINED_MILLIS = Long.MinValue

  final val MsecPerDay = 1000*60*60*24
  final val MsecPerHour = 1000*60*60

  //--- the constructors

  @inline def now: DateTime = new DateTime(System.currentTimeMillis)
  @inline def ofEpochMillis(millis: Long) = new DateTime(millis)

  @inline def apply(year: Int, month: Int, day: Int, hour: Int, minutes: Int, secs: Int, ms: Int, zoneId: ZoneId): DateTime = {
    val zdt = ZonedDateTime.of(year,month,day, hour,minutes,secs,ms * 1000000, zoneId)
    new DateTime(zdt.getLong(ChronoField.INSTANT_SECONDS) * 1000 + ms)
  }

  def timeBetween (a: DateTime, b: DateTime): Time = {
    if (a.millis >= b.millis) new Time((a.millis - b.millis).toInt)
    else new Time((b.millis - a.millis).toInt)
  }

  def parse(spec: String, dtf: DateTimeFormatter): DateTime = {
    val inst = Instant.from(dtf.parse(spec))
    new DateTime(inst.toEpochMilli)
  }

  @inline def parseISO (spec: String): DateTime = parse(spec, DateTimeFormatter.ISO_DATE_TIME)

  // examples: "2017-08-08T00:44:12Z" "2018-01-02T10:20:01.234+03:30[US/Pacific]"

  val YMDT_RE = """(\d{4})[\/-](\d{1,2})[\/-](\d{1,2})[ ,T](\d{1,2}):(\d{1,2}):(\d{1,2})(?:\.(\d{1,9}))? ?(?:([+-]\d{1,2})(?:\:(\d{1,2})))? ?\[?([\w\/]+)?+\]?""".r

  def parseYMDT(spec: String): DateTime = {
    spec match {
      case YMDT_RE(year,month,day,hour,min,sec,secFrac, offHour,offMin,zId) =>
        val zoneId = getZoneId(zId,offHour,offMin)

        val y = Integer.parseInt(year)
        val M = Integer.parseInt(month)
        val d = Integer.parseInt(day)
        val H = Integer.parseInt(hour)
        val m = Integer.parseInt(min)
        val s = Integer.parseInt(sec)
        val S = if (secFrac == null) 0 else getFracNanos(secFrac)

        val zdt = ZonedDateTime.of(y,M,d, H,m,s,S, zoneId)
        new DateTime(zdt.getLong(ChronoField.INSTANT_SECONDS) * 1000 + S / 1000000)

      case _ => throw new RuntimeException(s"invalid date spec: $spec")
    }
  }

  def getZoneId (zid: String, offHour: String, offMin: String): ZoneId = {
    if (offHour == null) {
      if (zid == null) ZoneId.systemDefault else ZoneId.of(zid)

    } else { // zone name is ignored
      val h = Integer.parseInt(offHour)
      val m = if (offMin == null) 0 else if (h < 0) -Integer.parseInt(offMin) else Integer.parseInt(offMin)
      ZoneId.ofOffset("", ZoneOffset.ofHoursMinutes(h, m))
    }
  }

  def getFracNanos(secFrac: String): Int = {
    val n = Integer.parseInt(secFrac)
    var s = 1000000000
    var i = secFrac.length
    while (i > 0) {
      s /= 10
      i -= 1
    }
    n * s
  }


  //--- slice based parsing
  // TODO - use the component extractors to avoid redundancy

  def parseYMDT (slice: Slice): DateTime = parseYMDT(slice.data, slice.offset, slice.byteLength)

  def parseYMDT(bs: Array[Byte], off: Int, len: Int): DateTime = {
    var i = off
    val iMax = off + len

    @inline def readDigit (k: Int): Int = {
      val d = bs(k) - '0'
      if (d >= 0 && d <= 9) return d else throw new RuntimeException(s"invalid date spec: ${new String(bs,off,len)}")
    }

    @inline def read4Digits (k: Int): Int = readDigit(k)*1000 + readDigit(k+1)*100 + readDigit(k+2)*10 + readDigit(k+3)
    @inline def read2Digits (k: Int): Int = readDigit(k)*10 + readDigit(k+1)

    def readFracNanos: Int = {
      var d = 0
      var s = 100000000
      var b = bs(i)
      while (b >= '0' && b <= '9') {
        d = d + (b - '0')*s
        s /= 10
        i += 1
        if (i < iMax) b = bs(i) else return d
      }
      d
    }

    //--- fixed numeric fields
    val y = read4Digits(i)
    val M = read2Digits(i+5)
    val d = read2Digits(i+8)
    val H = read2Digits(i+11)
    val m = read2Digits(i+14)
    val s = read2Digits(i+17)

    //--- variable and optional fields
    var S = 0
    var offH = 0
    var offMin = 0
    var zoneId: ZoneId = ZoneId.systemDefault

    i = off+19
    if (i < iMax) {
      var b = bs(i)
      if (b == '.') { // optional fractional nanos
        i += 1
        S = readFracNanos
        b = if (i < iMax) bs(i) else 0
      }

      if (b == '+' || b == '-') { // optional zone offset (id is ignored)
        offH = read2Digits(i + 1)
        if (b == '-') offH = -offH
        i += 3
        if (bs(i) == ':') {
          offMin = read2Digits(i + 1)
          if (offH < 0) offMin = -offMin
          i += 3
        }
        zoneId = ZoneId.ofOffset("", ZoneOffset.ofHoursMinutes(offH, offMin))

      } else { // is there a zone id?
        if (b == 'Z') {
          zoneId = ZoneOffset.UTC

        } else if (b == '[') {
          i += 1
          if ((iMax - i >= 3) &&
            (bs(i) == 'U' && bs(i + 1) == 'T' && bs(i + 2) == 'C') ||
            (bs(i) == 'G' && bs(i + 1) == 'M' && bs(i + 2) == 'T')) {
            ZoneOffset.UTC

          } else {
            val i0 = i
            while (bs(i) != ']') {
              i += 1
            }
            val zId = new String(bs, i0, i - i0) // unfortunately we have to allocate since ZoneId does not provide other accessors
            ZoneId.of(zId)
          }
        }
      }
    }

    val zdt = ZonedDateTime.of(y,M,d, H,m,s,S, zoneId) // another allocation we can't avoid
    new DateTime(zdt.getLong(ChronoField.INSTANT_SECONDS) * 1000 + S / 1000000)
  }

  //--- component based DateTime parsing (in case we have to construct from separate date and time inputs

  @inline private def isDigit (b: Byte): Boolean = (b >= '0' && b <= '9')
  @inline private def readDigit (bs: Array[Byte], k: Int): Int = {
    val d = bs(k) - '0'
    if (d >= 0 && d <= 9) return d else throw new RuntimeException(s"not a digit: ${bs(k).toChar}")
  }
  @inline private def read4Digits (bs: Array[Byte], k: Int): Int = readDigit(bs,k)*1000 + readDigit(bs,k+1)*100 + readDigit(bs,k+2)*10 + readDigit(bs,k+3)
  @inline private def read2Digits (bs: Array[Byte], k: Int): Int = readDigit(bs,k)*10 + readDigit(bs,k+1)

  @inline def yearOfYMD (slice: Slice): Int = yearOfYMD(slice.data,slice.offset,slice.byteLength)
  @inline def yearOfYMD (bs: Array[Byte], off: Int, len: Int): Int = read4Digits(bs,off)

  @inline def monthOfYMD (slice: Slice): Int = monthOfYMD(slice.data,slice.offset,slice.byteLength)
  @inline def monthOfYMD (bs: Array[Byte], off: Int, len: Int): Int = read2Digits(bs,off+5)

  @inline def dayOfYMD (slice: Slice): Int = dayOfYMD(slice.data,slice.offset,slice.byteLength)
  @inline def dayOfYMD (bs: Array[Byte], off: Int, len: Int): Int = read2Digits(bs,off+8)

  @inline def hourOfT (slice: Slice): Int = hourOfT(slice.data,slice.offset,slice.byteLength)
  @inline def hourOfT (bs: Array[Byte], off: Int, len: Int): Int = read2Digits(bs,off)

  @inline def minutesOfT (slice: Slice): Int = minutesOfT(slice.data,slice.offset,slice.byteLength)
  @inline def minutesOfT (bs: Array[Byte], off: Int, len: Int): Int = read2Digits(bs,off+3)

  @inline def secondsOfT (slice: Slice): Int = secondsOfT(slice.data,slice.offset,slice.byteLength)
  @inline def secondsOfT (bs: Array[Byte], off: Int, len: Int): Int = read2Digits(bs,off+6)

  @inline def fracNanosOfT (slice: Slice): Int = fracNanosOfT(slice.data,slice.offset,slice.byteLength)
  def fracNanosOfT (bs: Array[Byte], off: Int, len: Int): Int = {
    var i = off + 8
    val iMax = off + len
    if (i >= iMax || bs(i) != '.') return 0 // no fractional part
    i += 1

    var S = 0
    var s = 100000000
    while (i < iMax) {
      val b = bs(i)
      if (!isDigit(b)) return S
      S = S + (b - '0')*s
      s /= 10
      i += 1
    }
    S
  }

  def zoneIdOfHHmmssS (slice: Slice): ZoneId = {
    val off1 = zoneIdOffsetOfT(slice.data,slice.offset+8,slice.byteLength-8)
    if (off1 >= 0)  zoneIdOfT(slice.data,off1,slice.byteLength-(off1-slice.offset)) else ZoneId.systemDefault
  }

  @inline def zoneIdOffsetOfT (slice: Slice): Int = zoneIdOffsetOfT(slice.data,slice.offset,slice.byteLength)
  def zoneIdOffsetOfT (bs: Array[Byte], off: Int, len: Int): Int = {
    var i = off
    val iMax = off+len

    while (i < iMax && isDigit(bs(i))) i += 1
    if (i == iMax) return -1 // no zone id spec

    if (bs(i) == '.') { // optional fractional seconds
      i += 1
      while (i < iMax && isDigit(bs(i))) i += 1
      if (i == iMax) return -1 // no zone id spec
    }

    i
  }

  // apply zoneIdOffsetOfT before calling these
  @inline def zoneIdOfT (slice: Slice): ZoneId = zoneIdOfT(slice.data,slice.offset,slice.byteLength)
  def zoneIdOfT (bs: Array[Byte], off: Int, len: Int): ZoneId = {
    // optional zone id starts either with +/- or a non-digit other than '.'
    var i = off
    val iMax = off+len

    if (i >= iMax) return ZoneId.systemDefault // nothing specified, fall back to system configuration

    val b = bs(i)
    var offH: Int = 0
    var offMin: Int = 0

    if (b == '+' || b == '-') { // zone offset (id is ignored)
      offH = read2Digits(bs, i+1)
      if (b == '-') offH = -offH
      i += 3
      if (bs(i) == ':') {
        offMin = read2Digits(bs, i+1)
        if (offH < 0) offMin = -offMin
        i += 3
      }
      ZoneId.ofOffset("", ZoneOffset.ofHoursMinutes(offH,offMin))

    } else { // is there a zone id?
      if (b == 'Z') {
        ZoneOffset.UTC

      } else if (b == '[') {
        i += 1
        if ((iMax - i >= 3) &&
          (bs(i) == 'U' && bs(i+1) == 'T' && bs(i+2) == 'C') ||
          (bs(i) == 'G' && bs(i+1) == 'M' && bs(i+2) == 'T')) {
          ZoneOffset.UTC

        } else {
          val i0 = i
          while (bs(i) != ']') {
            i += 1
          }
          val zId = new String(bs, i0, i - i0) // unfortunately we have to allocate since ZoneId does not provide other accessors
          ZoneId.of(zId)
        }
      } else {
        throw new RuntimeException(s"not a valid time zone spec: ${new String(bs,off,len)}")
      }
    }
  }


  //--- convenience methods for epoch milli conversion
  def epochMillisToString (millis: Long): String = new DateTime(millis).format_yMd_HmsS_z
  def hoursBetweenEpochMillis (t1: Long, t2: Long): Double = (t2 - t1).toDouble / MsecPerHour
  def daysBetweenEpochMillis (t1: Long, t2: Long): Double = (t2 - t1).toDouble / MsecPerDay

  //--- constants

  val Date0 = new DateTime(0)
  val UndefinedDateTime = new DateTime(UNDEFINED_MILLIS)

  val LocalOffsetMillis: Long = ZonedDateTime.now.getOffset.getTotalSeconds * 1000
  val LocalOffsetHours: Int = ZonedDateTime.now.getOffset.getTotalSeconds / 3600
  val LocalOffsetMinutes: Int = ZonedDateTime.now.getOffset.getTotalSeconds / 60
  val LocalZoneId: ZoneId = ZoneId.systemDefault
  val LocalZoneIdName: String = LocalZoneId.getDisplayName(TextStyle.SHORT,Locale.getDefault)
  val GMTZoneId: ZoneId = ZoneId.of("GMT")
}
import gov.nasa.race.uom.DateTime._

/**
  * value class representing absolute time value based on Unix epoch time (ms since 01/01/1970-00:00:00)
  *
  * NOTE - this is stored as UTC epoch millis - we don't store a time zone or offset. TimeZones have
  * to be explicitly provided in methods that are zone specific
  */
class DateTime protected[uom](val millis: Long) extends AnyVal
                         with Ordered[DateTime] with MaybeUndefined {

  @inline def toEpochMillis: Long = millis
  @inline def toLocalMillis: Long = (millis + LocalOffsetMillis)

  @inline def toInstant: Instant = Instant.ofEpochMilli(millis)
  @inline def toZonedDateTime (zoneId: ZoneId): ZonedDateTime = {
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis),zoneId)
  }
  @inline def toZonedDateTime: ZonedDateTime = toZonedDateTime(GMTZoneId)
  @inline def toLocalZonedDateTime: ZonedDateTime = toZonedDateTime(LocalZoneId)

  def toFiniteDuration: FiniteDuration = {
    if (millis != UNDEFINED_MILLIS) FiniteDuration(millis,MILLISECONDS)
    else throw new DateException("undefined Date value")
  }

  //--- field accessors

  @inline def getYear: Int = toZonedDateTime.getYear
  @inline def getYear (zoneId: ZoneId): Int = toZonedDateTime(zoneId).getYear
  @inline def getLocalYear: Int = toZonedDateTime(LocalZoneId).getYear

  @inline def getMonthValue: Int = toZonedDateTime.getMonthValue
  @inline def getMonthValue (zoneId: ZoneId): Int = toZonedDateTime(zoneId).getMonthValue
  @inline def getLocalMonthValue: Int = getMonthValue(LocalZoneId)

  @inline def getDayOfMonth: Int = toZonedDateTime.getDayOfMonth
  @inline def getDayOfMonth (zoneId: ZoneId): Int = toZonedDateTime(zoneId).getDayOfMonth
  @inline def getLocalDayOfMonth: Int = getDayOfMonth(LocalZoneId)

  @inline def getYMD: (Int,Int,Int) = {
    val zdt = toZonedDateTime
    (zdt.getYear, zdt.getMonthValue, zdt.getDayOfMonth)
  }
  @inline def getYMD (zoneId: ZoneId): (Int,Int,Int) = {
    val zdt = toZonedDateTime(zoneId)
    (zdt.getYear, zdt.getMonthValue, zdt.getDayOfMonth)
  }
  @inline def getLocalYMD: (Int,Int,Int) = getYMD(LocalZoneId)

  @inline def getYMDT: (Int,Int,Int, Int,Int,Int,Int) = {
    val zdt = toZonedDateTime
    (zdt.getYear, zdt.getMonthValue, zdt.getDayOfMonth, zdt.getHour, getMinute, getSecond, getMillisecond)
  }
  @inline def getYMDT (zoneId: ZoneId): (Int,Int,Int, Int,Int,Int,Int) = {
    val zdt = toZonedDateTime(zoneId)
    (zdt.getYear, zdt.getMonthValue, zdt.getDayOfMonth, zdt.getHour, getMinute, getSecond, getMillisecond)
  }
  @inline def getLocalYMDT: (Int,Int,Int, Int,Int,Int,Int) = {
    val zdt = toLocalZonedDateTime
    (zdt.getYear, zdt.getMonthValue, zdt.getDayOfMonth, zdt.getHour, getMinute, getSecond, getMillisecond)
  }

  //--- these refer to UTC
  @inline def getHour: Int = ((millis % Time.MillisInDay) / Time.MillisInHour).toInt
  @inline def getHour (zoneId: ZoneId): Int = toZonedDateTime(zoneId).getHour
  @inline def getLocalHour: Int = (((millis + LocalOffsetMillis) % Time.MillisInDay) / Time.MillisInHour).toInt

  @inline def getMinute: Int = ((millis % Time.MillisInHour) / Time.MillisInMinute).toInt
  @inline def getLocalMinute: Int = (((millis + LocalOffsetMillis) % Time.MillisInHour) / Time.MillisInMinute).toInt

  // we assume only full minute offsets so second and millisecond do not depend on zone

  @inline def getSecond: Int = ((millis % Time.MillisInMinute) / 1000).toInt
  @inline def getMillisecond: Int = (millis % 1000).toInt


  def getTimeOfDay: Time = new Time((millis % Time.MillisInDay).toInt)
  def getLocalTimeOfDay: Time = new Time(((millis + LocalOffsetMillis) % Time.MillisInDay).toInt)

  // unfortunately we can't overload '-' because of erasure
  @inline def timeUntil(d: DateTime): Time = new Time((d.millis - millis).toInt)
  def :-> (d: DateTime): Time = timeUntil(d)

  @inline def timeSince(d: DateTime): Time = new Time((millis - d.millis).toInt)
  def <-: (d: DateTime): Time = timeSince(d)
  @inline def - (d: DateTime): Time = new Time((millis - d.millis).toInt)

  @inline def + (t: Time): DateTime = new DateTime(millis + t.millis)
  @inline def - (t: Time): DateTime = new DateTime(millis - t.millis)

  @inline def + (dur: Duration): DateTime = new DateTime(millis + dur.toMillis)
  @inline def - (dur: Duration): DateTime = new DateTime(millis - dur.toMillis)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isDefined: Boolean = millis != UNDEFINED_MILLIS
  @inline override def isUndefined: Boolean = millis == UNDEFINED_MILLIS
  @inline def orElse (f: => DateTime): DateTime = if (isDefined) this else f
  @inline def map (f: DateTime => DateTime): DateTime = if (isDefined) f(this) else DateTime.UndefinedDateTime

  //--- comparison (TODO - handle undefined values)
  @inline override def < (o: DateTime): Boolean = millis < o.millis
  @inline override def <= (o: DateTime): Boolean = millis <= o.millis
  @inline override def > (o: DateTime): Boolean = millis > o.millis
  @inline override def >= (o: DateTime): Boolean = millis >= o.millis

  @inline override def compare (other: DateTime): Int = if (millis > other.millis) 1 else if (millis < other.millis) -1 else 0
  @inline override def compareTo (other: DateTime): Int = compare(other)

  @inline def isAfter (o: DateTime): Boolean = millis > o.millis
  @inline def isBefore (o: DateTime): Boolean = millis < o.millis
  @inline def == (o: DateTime): Boolean = millis == o.millis

  //--- formatting

  def format (fmt: DateTimeFormatter): String = toZonedDateTime.format(fmt)
  def format(fmtSpec: String): String = format(DateTimeFormatter.ofPattern(fmtSpec))

  def format (fmt: DateTimeFormatter, zoneId: ZoneId): String = toZonedDateTime(zoneId).format(fmt)
  def format(fmtSpec: String, zoneId: ZoneId): String = format(DateTimeFormatter.ofPattern(fmtSpec),zoneId)

  def formatLocal (fmt: DateTimeFormatter): String = toZonedDateTime(LocalZoneId).format(fmt)
  def formatLocal (fmtSpec: String): String = formatLocal(DateTimeFormatter.ofPattern(fmtSpec))

  //--- simple formatting

  def format_yMd_HmsS_z: String = {
    val (year,month,day) = getYMD
    f"$year%4d-$month%02d-$day%02dT$getHour%02d:$getMinute%02d:$getSecond%02d.$getMillisecond%03dZ"
  }
  def format_yMd_Hms_z: String = {
    val (year,month,day) = getYMD
    f"$year%4d-$month%02d-$day%02dT$getHour%02d:$getMinute%02d:$getSecond%02dZ"
  }
  def format_yMd_Hms_z(zoneId: ZoneId): String = {
    val (year,month,day,hour,minute,second,_) = getYMDT(zoneId)
    val shortId = zoneId.getDisplayName(TextStyle.SHORT,Locale.getDefault)
    f"$year%4d-$month%02d-$day%02dT$hour%02d:$minute%02d:$second%02d$shortId"
  }
  def formatLocal_yMd_Hms_z: String = {
    val (year,month,day,hour,minute,second,_) = getYMDT(LocalZoneId)
    f"$year%4d-$month%02d-$day%02dT$hour%02d:$minute%02d:$second%02d$LocalZoneIdName"
  }
  def formatLocal_yMd_Hms: String = {
    val (year,month,day,hour,minute,second,_) = getYMDT(LocalZoneId)
    f"$year%4d-$month%02d-$day%02dT$hour%02d:$minute%02d:$second%02d"
  }

  def format_yMd: String = {
    val (year,month,day) = getYMD
    f"$year%4d-$month%02d-$day%02d"
  }

  def format_E_Mdy (zdt: ZonedDateTime): String = {
    val year = zdt.getYear
    val month = zdt.getMonthValue
    val day = zdt.getDayOfMonth
    val dayName = zdt.getDayOfWeek.getDisplayName(TextStyle.SHORT,Locale.getDefault)
    f"$dayName $month%02d-$day%02d-$year%4d"
  }

  def format_E_Mdy: String = format_E_Mdy(toZonedDateTime)

  def formatLocal_E_Mdy: String = format_E_Mdy(toLocalZonedDateTime)

  def format_Hms: String = f"$getHour%02d:$getMinute%02d:$getSecond%02d"

  def format_Hms_z: String = f"$getHour%02d:$getMinute%02d:$getSecond%02d Z"

  def formatLocal_Hms: String = {
    val hour = getLocalHour
    val minute = getLocalMinute
    val second = getSecond
    f"$hour%02d:$minute%02d:$second%02d"
  }

  def formatLocal_Hms_z: String = {
    val hour = getLocalHour
    val minute = getLocalMinute
    val second = getSecond
    f"$hour%02d:$minute%02d:$second%02d $LocalZoneIdName"
  }

  override def toString: String = {
    if (millis != UNDEFINED_MILLIS) format_yMd_HmsS_z else "undefined"
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

