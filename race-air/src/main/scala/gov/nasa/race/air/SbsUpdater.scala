package gov.nasa.race.air

import gov.nasa.race.archive.DateAdjuster
import gov.nasa.race.common.{ConstAsciiSlice, Utf8CsvPullParser}
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.{TrackedObject,Tracked3dObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.DateTime._
import gov.nasa.race.uom.{Angle, DateTime, Speed, Time}
import gov.nasa.race.uom.Speed._

import java.time.ZoneId
import scala.collection.mutable


/**
  * CsvPullParser for raw SBS text data
  *
  * note this is used for both online (tcp stream) parsing and for replay - we have to make sure we get a complete
  * record before parsing the next message
  *
  * NOTE - this parser is stateful since we receive position, altitude/speed and callsign in different
  * SBS messages. We only create FlightPos objects once we receive a position update for which we have the complete
  * information.
  *
  * SBS as documented on http://woodair.net/SBS/Article/Barebones42_Socket_Data.htm
  * Message examples:
  *  MSG,1,111,11111,AA2BC2,111111,2016/03/11,13:07:16.663,2016/03/11,13:07:16.626,UAL814  ,,,,,,,,,,,0
  *  MSG,3,111,11111,A04424,111111,2016/03/11,13:07:05.343,2016/03/11,13:07:05.288,,11025,,,37.17274,-122.03935,,,,,,0
  *  MSG,4,111,11111,AC1FCC,111111,2016/03/11,13:07:07.777,2016/03/11,13:07:07.713,,,316,106,,,1536,,,,,0
  *
  * fields:
  *   1: message type (MSG, SEL, ID, AIR, STA, CLK)
  *   2: transmission type (MSG only: 1-8, 3: ES Airborne Position Message)
  *   3: DB session id   - '111' for dump1090 generated SBS
  *   4: DB aircraft id  - '11111' for dump1090 generated SBS
  *   5: ICAO 24 bit id (mode S transponder code)
  *   6: DB flight id - '111111' for dump1090 generated SBS
  *   7: date generated
  *   8: time generated
  *   9: date logged
  *  10: time logged
  *  11: callsign
  *  12: mode-C altitude (relative to 1013.2mb (Flight Level), *not* AMSL)
  *  13: ground speed
  *  14: track (from vx,vy, *not* heading)
  *  15: latitude
  *  16: longitude
  *  17: vertical rate (ft/min - 64ft resolution)
  *  18: squawk (mode-A squawk code)
  *  19: alert (flag indicating squawk has changed)
  *  20: emergency (flag)
  *  21: spi (flag, transponder ident activated)
  *  22: on ground (flag)
  *
  *  see also http://mode-s.org/decode/
  */
class SbsUpdater (adjuster: Option[DateAdjuster] = None, defaultZone: ZoneId = ZoneId.systemDefault()) extends Utf8CsvPullParser {

  /**
    * aux object to store aircraft info we get from type 1,4 MSG records
    * that is required to fill in the missing FlightPos fields when receiving a type 3 MSG
    */
  class AcCacheEntry(val icao24: Long) {
    var publishDate: DateTime = UndefinedDateTime // when was the last FPos published

    //--- from MSG-1 (no need to store time since it is invariant)
    var cs: String = null
    var icao24String: String = null // not set before we have a cs

    //--- from MSG-4
    var spd: Speed = UndefinedSpeed
    var vr: Speed = UndefinedSpeed
    var hdg: Angle = UndefinedAngle
    var msg4Date: DateTime = UndefinedDateTime

    def isComplete: Boolean = {
      (cs != null) && hdg.isDefined && spd.isDefined
    }
  }

  val _MSG_ = ConstAsciiSlice("MSG")

  var nUpdates: Long = 0 // since start
  var nDropped: Long = 0 // since start
  var nRemoved: Long = 0 // since last reorg

  // we choose a LongMap based on the assumption that size is limited (<100 entries) and update is much more
  // frequent than removal (LongMap is an open hashmap that requires reorganization)
  val acCache = new mutable.LongMap[AcCacheEntry](128) // keys are icao24 ids (hex 24bit)

  @inline def getAdjustableDate (date: DateTime): DateTime = {
    if (adjuster.isDefined) adjuster.get.getDate(date) else date
  }

  /**
    * parse one record (line) and call the provided update func if that line completes an AcCacheEntry
    *
    * this has to be called after initalize(data,limit) so that we have something to parse
    * the underlying data has to hold a complete record
    */
  def parse( updateFunc: Tracked3dObject=>Unit): Unit = {
    def readDate: DateTime = {
      val dateRange = readNextValue().getIntRange
      val timeRange = readNextValue().getIntRange
      getAdjustableDate( DateTime.parseYMDTBytes(data,dateRange.offset,dateRange.length+timeRange.length+1, defaultZone))
    }

    while (hasMoreData) {
      if (readNextValue() == _MSG_) {
        readNextValue().toInt match {
          case 1 => // flight identification (cs)
            skip(2)
            parseNextValue()
            val icao24 = value.toHexLong
            val icao24String = value.intern
            skip(5)
            parseNextValue()
            value.trimSelf // dump1090 reports c/s with spaces filled to 8 chars
            val cs = value.intern
            val ac = acCache.getOrElseUpdate(icao24, new AcCacheEntry(icao24))
            ac.cs = cs
            ac.icao24String = icao24String

          // we could use MSG,2 for (surface movement) to cleanup/drop, but we won't get them with a normal antenna
          // since ADS-B is line-of-sight and the signal is most likely cutting out close to the ground

          case 3 => // airborne position (date,lat,lon,alt) - those are the only ones we report once we have a complete data set
            skip(2)
            val icao24 = readNextValue().toHexLong

            val ac = acCache.getOrNull(icao24) // avoid allocation
            if (ac != null) {
              if (ac.isComplete) {
                skip(1)
                val date = readDate
                skip(3)
                val alt = if (parseNextNonEmptyValue()) Feet(value.toInt) else UndefinedLength
                skip(2)
                val lat = if (parseNextNonEmptyValue()) Degrees(value.toDouble) else UndefinedAngle
                val lon = if (parseNextNonEmptyValue()) Degrees(value.toDouble) else UndefinedAngle

                if (lat.isDefined && lon.isDefined && alt.isDefined) { // Ok, we have something to update
                  nUpdates += 1
                  val status = if (ac.publishDate.isDefined) 0 else TrackedObject.NewFlag
                  ac.publishDate = date // we only record the last time for which we got a position

                  val fpos = new FlightPos(ac.icao24String, ac.cs, LatLonPos(lat, lon, alt), ac.spd, ac.hdg, ac.vr, date, status)
                  //println(s"@@@ update with ${acCache.size} / ${acCache.values.filter(_.isComplete).size}")
                  updateFunc(fpos)
                }
              }
            }

          case 4 => // airborne velocity (date,spd,vr,hdg)
            skip(2)
            val icao24 = readNextValue().toHexLong
            skip(1)
            val date = readDate
            skip(4)

            // apparently some aircraft report MSG4 without speed and heading
            val spd = if (parseNextNonEmptyValue()) Knots(value.toInt) else UndefinedSpeed
            val hdg = if (parseNextNonEmptyValue()) Degrees(value.toInt) else UndefinedAngle
            skip(2)
            val vr = if (parseNextNonEmptyValue()) FeetPerMinute(value.toInt) else UndefinedSpeed

            val ac = acCache.getOrElseUpdate(icao24, new AcCacheEntry(icao24))
            ac.msg4Date = date
            ac.spd = spd
            ac.hdg = hdg
            ac.vr = vr

          case _ => // ignore other MSG types
        }
      }
      skipToNextRecord()
    }
  }

  /**
    * timout based cleanup function in case we don't get MSG2 (surface movement) messages
    *
    * this is here since we already have a map of all active flights for our data accumulation
    */
  def dropStale (date: DateTime, dropAfter: Time, dropFunc: (String,String,DateTime,Time)=>Unit): Unit = {
    acCache.foreachValue { ac=>
      if (ac.publishDate.isDefined) { // only defined once we have published this ac
        val dt = date.timeSince(ac.publishDate)
        if (dt > dropAfter) {
          acCache.remove(ac.icao24)
          dropFunc(ac.icao24String, ac.cs, date, dt)
          nDropped += 1
          nRemoved += 1

          if (nRemoved > acCache.size / 10) {
            acCache.repack()
            nRemoved = 0
          }
        }
      }
    }
  }
}