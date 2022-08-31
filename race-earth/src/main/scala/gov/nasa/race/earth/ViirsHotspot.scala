/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.earth

import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveEntry, StreamArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.{FMT_1_1, FMT_1_2, FMT_3_1, FMT_3_5, JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.{GeoPosition, GeoPositionFilter}
import gov.nasa.race.earth.Hotspot._
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime.{NeverDateTime, UndefinedDateTime}
import gov.nasa.race.uom.Length.{Kilometers, Meters}
import gov.nasa.race.uom.Power.MegaWatt
import gov.nasa.race.uom.Temperature.Kelvin
import gov.nasa.race.uom.Time.HM
import gov.nasa.race.uom.{Angle, DateTime, Length, Power, Temperature, Time}
import gov.nasa.race.{DatedOrdering, Failure, ResultValue, SuccessValue}

import java.io.InputStream
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * represents a fire pixel as reported by the VIIRS Active Fire product
  * see https://viirsland.gsfc.nasa.gov/PDF/VIIRS_activefire_User_Guide.pdf
  */
class ViirsHotspot (
                     val satId: Int,

                     //--- fields parsed from external source (CSV file)
                     val date: DateTime,
                     val position: GeoPosition, // geo center of pixel
                     val source: String, // N: Suomi NPP, 1: JPSS-1, A: Aqua, T: Terra

                     val brightness: Temperature, // pixel temp in K
                     val brightness1: Double, // secondary channel pixel temp in K
                     val frp: Power, // pixel-integrated fire radiated power in MW
                     val scan: Length, // actual cross-track pixel size in km
                     val track: Length, // actual along-track pixel size in km
                     val sensor: String,
                     val confidence: Int,
                     val version: String,
                     val isDay: Boolean,

                     //--- computed fields
                     val bounds: Seq[GeoPosition] = Seq.empty, // pixel bounds

                     val pixelSize: Length = Meters(375) // TODO - remove, use real bounds based on overpass azimuth
                   ) extends Hotspot {

  override def temp: Temperature = brightness

  override def isGoodPixel: Boolean = confidence == HIGH_CONFIDENCE

  override def isProbablePixel: Boolean = confidence == NOMINAL_CONFIDENCE

  override def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeIntMember(SAT_ID,satId)
      .writeDateTimeMember(DATE,date)
      .writeDoubleMember(LAT, position.latDeg,FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg,FMT_3_5)
      .writeDoubleMember(TEMP, temp.toKelvin,FMT_3_1)
      .writeDoubleMember(FRP, frp.toMegaWatt,FMT_1_1)
      .writeDoubleMember(SCAN, scan.toKilometers,FMT_1_2)
      .writeDoubleMember(TRACK, track.toKilometers,FMT_1_2)
      .writeStringMember(SOURCE,source)
      .writeStringMember(VERSION, version)
      .writeIntMember(CONF, confidence)
      .writeBooleanMember(DAY, isDay)
      .writeArrayMember(BOUNDS)(serializeBoundsTo)
      .writeIntMember(SIZE, pixelSize.toRoundedMeters.toInt)
  }

  override def toString: String = s"ViirsHotspot($date, ${position.toLatLonString(5)}, $source, $version, $brightness, $frp, $scan, $track)"
}

object ViirsHotspots {

  /**
   * partition a seq of ViirsHotspot objects (which are not necessarily time-sorted) into a sorted sequence of ViirsHotspots objects.
   * Use the provided maxOverpassDuration to either deduce overpass time from the hotspot objects, or
   * to map into the provided sequence of computed overpass times.
   * This reflects the problem that we (can) get corrected data for previous overpasses in subsequent data sets, and that some of these
   * hotspots can precede the first computed overpass times
   */
  def partition(satId: Int, src: String, hs: Seq[ViirsHotspot], maxOverpassDuration: Time, overpasses: Seq[DateTime]): Seq[ViirsHotspots] = {
    val parts = mutable.SortedMap.empty[DateTime,ArrayBuffer[ViirsHotspot]]

    //--- the time window for which we map into overpasses
    val start = if (overpasses.nonEmpty) overpasses.head - maxOverpassDuration else NeverDateTime
    val end = if (overpasses.nonEmpty) overpasses.last else UndefinedDateTime

    //--- step 1: sort into bins
    hs.foreach { h =>
      val hd = h.date
      val od = getOverpass(hd, start, end, overpasses)
      val hotspots = parts.getOrElseUpdate(od, ArrayBuffer.empty)
      hotspots += h
    }

    //--- step 2: merge bins according to maxOverpassDuration
    var prevDate = UndefinedDateTime
    var prevHs = ArrayBuffer.empty[ViirsHotspot]
    parts.foreach { e=>
      val od = e._1
      if (prevDate.isDefined && od.timeSince(prevDate) < maxOverpassDuration) {  // merge, assumed to be same overpass
        e._2.prependAll( prevHs)
        parts.remove(prevDate)
      }
      prevDate = od
      prevHs = e._2
    }

    //--- step 3: turn into date sorted ViirsHotspots sequence
    parts.foldRight(Seq.empty[ViirsHotspots])( (e,acc) => {
      val (date,hotspots) = e
      ViirsHotspots( date, satId, src, hotspots.sortInPlaceWith((a,b)=> a.date < b.date).toSeq) +: acc
    })
  }

  def getOverpass (d: DateTime, start: DateTime, end: DateTime, overpasses: Seq[DateTime]): DateTime = {
    if (d >= start && d <= end) {
      overpasses.foreach { od => if (od > d) return od }
    }
    d
  }
}

case class ViirsHotspots (date: DateTime, satId: Int, src: String, elems: Seq[ViirsHotspot]) extends Hotspots[ViirsHotspot] {
  override def toString(): String = s"ViirsHotspots(${date},$satId,$src,${elems.size})"
}

/**
  * CSV parser for VIIRS data received from https://firms.modaps.eosdis.nasa.gov/usfs/active_fire/
  *
  * latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_t31,frp,daynight
  * 38.61877,-121.29271,308.4,0.43,0.46,2020-08-16,1024,1,VIIRS,n,2.0NRT,293.7,1.3,N
  * ...
  */
trait ViirsHotspotParser extends Utf8CsvPullParser {

  def readDate(): DateTime = {
    val d = DateTime.parseYMD(readNextValue(), DateTime.utcId)
    val hhmm = readNextValue().toInt
    val h = hhmm / 100
    val m = hhmm % 100
    val t = HM(h,m)
    d + t
  }

  def readConfidence(): Int = {
    readNextValue().charAt(0) match {
      case 'l' => LOW_CONFIDENCE
      case 'n' => NOMINAL_CONFIDENCE
      case 'h' => HIGH_CONFIDENCE
      case _ => -1
    }
  }

  def readDayNight(): Boolean = {
    val c = readNextValue().charAt(0)
    (c == 'D' || c == 'd')
  }

  def parseHotspot (satId: Int): ResultValue[ViirsHotspot] = {
    val lat: Angle = Degrees(readNextValue().toDouble)
    val lon: Angle = Degrees(readNextValue().toDouble)
    val brightness = Kelvin(readNextValue().toDouble)
    val scan = Kilometers(readNextValue().toDouble)
    val track = Kilometers(readNextValue().toDouble)
    val date = readDate()
    val src = readNextValue().intern
    val sensor = readNextValue().intern
    val confidence = readConfidence()
    val version = readNextValue().intern // <version>{NRT,RT,URT}
    val brightness1 = readNextValue().toDouble
    val frp = MegaWatt(readNextValue().toDouble)
    val isDay = readDayNight()
    skipToNextRecord()

    if (lat.isUndefined || lon.isUndefined) return Failure("no position")
    if (date.isUndefined) return Failure("no date")
    if (confidence < 0) return Failure("unknown confidence")

    val pos = GeoPosition(lat,lon)
    val bounds = computeBounds(date,pos,scan,track)

    SuccessValue( new ViirsHotspot(satId, date,pos,src,brightness,brightness1,frp,scan,track,sensor,confidence,version, isDay, bounds))
  }

  // override if we have a satellite position for the pixel date so that we can compute the pixel projection on the ellipsoid
  protected def computeBounds (date: DateTime, pos: GeoPosition, scan: Length, track: Length): Seq[GeoPosition] = {
    Seq.empty
  }
}

/**
  * ArchiveReader that works straight off FIRMS VIIRS/MODIS csv archives, which are of the format
  *
  * latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_t31,frp,daynight
  * 38.61877,-121.29271,308.4,0.43,0.46,2020-08-16,1024,1,VIIRS,n,2.0NRT,293.7,1.3,N
  * ...
  *
  * this implementation returns batches of hotspots with the same (or earlier) acquisition time.
  * Note that FIRMS hotspot archives are *not* monotone in time, i.e. there are strings of hotspots interspersed
  * that can jump back in acquisition time.
  */
class ViirsHotspotArchiveReader (val satId: Int, val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int,
                                 geoFilter: Option[GeoPositionFilter] = None) extends StreamArchiveReader with ViirsHotspotParser {

  def this(conf: Config) = this( conf.getInt("satellite"), createInputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096),
                                 GeoPositionFilter.fromOptionalConfig(conf, "bounds"))

  val lineBuffer = new LineBuffer(iStream,bufLen)
  val hs = ArrayBuffer.empty[ViirsHotspot]

  protected var prev: ViirsHotspot = null
  protected var date = DateTime.UndefinedDateTime

  lineBuffer.nextLine() // skip the header line of the archive

  override def readNextEntry(): Option[ArchiveEntry] = {
    var src: String = ""
    hs.clear()

    def result: Option[ArchiveEntry] = if (hs.isEmpty) None else archiveEntry(date, ViirsHotspots(date, satId, src, hs.toSeq))

    if (prev != null) { // we have a leftover from our previous invocation
      date = prev.date
      hs += prev
      prev = null
    }

    while (lineBuffer.nextLine()) {
      if (initialize(lineBuffer)) {
        parseHotspot(satId) match {
          case SuccessValue(hotspot) =>
            src = if (src.isEmpty) hotspot.source else if (src != hotspot.source) "multiple" else src

            if (geoFilter.isEmpty || geoFilter.get.pass(hotspot.position)) {
              if (date.isUndefined) {
                date = hotspot.date
                hs += hotspot
              } else {
                if (hotspot.date == date) { // note that we break on every date change (also backjumps)
                  hs += hotspot
                } else {
                  prev = hotspot
                  return result
                }
              }
            }

          case Failure(_) => None // did not parse
        }
      }
    }
    result
  }
}