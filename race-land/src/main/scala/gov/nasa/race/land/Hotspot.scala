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
package gov.nasa.race.land

import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader, StreamArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.{Dated, Failure, ResultValue, SuccessValue}
import gov.nasa.race.common.{AssocSeq, JsonSerializable, JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.{GeoPosition, GeoPositionFilter, GeoPositioned}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Time.HM
import gov.nasa.race.uom.{Angle, DateTime, Length, Time}

import java.io.InputStream
import java.text.DecimalFormat
import scala.collection.mutable.ArrayBuffer

object Hotspot {
  //--- constants
  val LOW_CONFIDENCE = 0
  val NOMINAL_CONFIDENCE = 1
  val HIGH_CONFIDENCE = 2

  //--- lexical constants
  val DATE = asc("date")
  val LAT = asc("lat")
  val LON = asc("lon")
  val BRIGHT = asc("brightness")
  val FRP = asc("frp")
  val SAT = asc("sat")
  val CONF = asc("conf")
  val SIZE = asc("size")
  val DAY = asc("day")

  val FMT_3_1 = new DecimalFormat("###.#")
  val FMT_1_1 = new DecimalFormat("#.#")
  val FMT_3_5 = new DecimalFormat("###.#####")
}
import Hotspot._


/**
  * represents a fire pixel as measured by VIIRS (visible infrared radiometer imaging suite) on board of
  * Suomi NPP or JPSS-1, or MODIS (Moderate Resolution Imaging Spectroradiometer) on board of Aqua and Terra
  */
class Hotspot (
                //--- fields parsed from external source (CSV file)
                val date: DateTime,
                val position: GeoPosition, // geo center of pixel
                val brightness: Double, // pixel temp in K
                val brightness1: Double, // secondary channel pixel temp in K
                val frp: Double, // pixel-integrated fire radiated power in MW
                val scan: Double, // actual pixel size factor
                val track: Double,
                val satellite: String,  // N: Suomi NPP, 1: JPSS-1, A: Aqua, T: Terra
                val confidence: Int,
                val version: String,
                val isDay: Boolean,
                //-- fixed fields
                val pixelSize: Length = Meters(375) // at nadir
              ) extends Dated with GeoPositioned with JsonSerializable {


  override def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeDateTimeMember(DATE,date)
      .writeDoubleMember(LAT, position.latDeg,FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg,FMT_3_5)
      .writeDoubleMember(BRIGHT, brightness,FMT_3_1)
      .writeDoubleMember(FRP, frp,FMT_1_1)
      .writeStringMember(SAT,satellite)
      .writeIntMember(CONF, confidence)
      .writeBooleanMember(DAY, isDay)
      .writeIntMember(SIZE, pixelSize.toMeters.toInt)
  }
}

/**
  * a matchable type for a fixed sequence of Hotspot objects
  * note that timestamps in the included hotspots do not need to be monotonic (some FIRMS archives have backjumps)
  */
case class Hotspots (date: DateTime, hotspots: Array[Hotspot]) extends Seq[Hotspot] with JsonSerializable with Dated {

  def serializeMembersTo (writer: JsonWriter): Unit = {
    writer.beginArray
    foreach( _.serializeTo(writer))
    writer.endArray
  }

  override def apply(i: Int): Hotspot = hotspots(i)
  override def length: Int = hotspots.length
  override def iterator: Iterator[Hotspot] = hotspots.iterator
}

/**
  * CSV parser for VIIRS data received from https://firms.modaps.eosdis.nasa.gov/usfs/active_fire/
  *
  * latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_t31,frp,daynight
  * 38.61877,-121.29271,308.4,0.43,0.46,2020-08-16,1024,1,VIIRS,n,2.0NRT,293.7,1.3,N
  * ...
  */
trait HotspotParser extends Utf8CsvPullParser {

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

  def parseHotspot(): ResultValue[Hotspot] = {
    val lat: Angle = Degrees(readNextValue().toDouble)
    val lon: Angle = Degrees(readNextValue().toDouble)
    val brightness = readNextValue().toDouble
    val scan = readNextValue().toDouble
    val track = readNextValue().toDouble
    val date = readDate()
    val sat = readNextValue().intern
    skip(1) // we know the instrument
    val confidence = readConfidence()
    val version = readNextValue().intern // do we care?
    val brightness1 = readNextValue().toDouble
    val frp = readNextValue().toDouble
    val isDay = readDayNight()
    skipToNextRecord() // we don't care about daynight

    if (lat.isUndefined || lon.isUndefined) return Failure("no position")
    if (date.isUndefined) return Failure("no date")
    if (confidence < 0) return Failure("unknown confidence")
    SuccessValue( new Hotspot(date,GeoPosition(lat,lon),brightness,brightness1,frp,scan,track,sat,confidence,version, isDay))
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
class HotspotArchiveReader (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int,
                            geoFilter: Option[GeoPositionFilter] = None)
                                                   extends StreamArchiveReader with HotspotParser {
  def this(conf: Config) = this( createInputStream(conf),
                                 configuredPathName(conf),
                                 conf.getIntOrElse("buffer-size",4096),
                                 GeoPositionFilter.fromOptionalConfig(conf, "bounds"))

  val lineBuffer = new LineBuffer(iStream,bufLen)
  val hs = ArrayBuffer.empty[Hotspot]

  protected var prev: Hotspot = null
  protected var date = DateTime.UndefinedDateTime

  lineBuffer.nextLine() // skip the header line of the archive

  override def readNextEntry(): Option[ArchiveEntry] = {
    hs.clear()

    def result: Option[ArchiveEntry] = if (hs.isEmpty) None else archiveEntry(date, new Hotspots(date, hs.toArray))

    if (prev != null) { // we have a leftover from our previous invocation
      date = prev.date
      hs += prev
      prev = null
    }

    while (lineBuffer.nextLine()) {
      if (initialize(lineBuffer)) {
        parseHotspot() match {
          case SuccessValue(hotspot) =>
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