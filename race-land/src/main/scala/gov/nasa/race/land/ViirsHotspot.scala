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
import gov.nasa.race.{Failure, ResultValue, SuccessValue}
import gov.nasa.race.archive.{ArchiveEntry, StreamArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.{JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.{GeoPosition, GeoPositionFilter}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Temperature.Kelvin
import gov.nasa.race.uom.{Angle, DateTime, Length, Power, Temperature}
import gov.nasa.race.uom.Time.HM
import gov.nasa.race.land.Hotspot._
import gov.nasa.race.uom.Power.MegaWatt

import java.io.InputStream
import scala.collection.mutable.ArrayBuffer

/**
  * represents a fire pixel as reported by the VIIRS Active Fire product
  * see https://viirsland.gsfc.nasa.gov/PDF/VIIRS_activefire_User_Guide.pdf
  */
class ViirsHotspot (
                     //--- fields parsed from external source (CSV file)
                     val date: DateTime,
                     val position: GeoPosition, // geo center of pixel
                     val source: String, // N: Suomi NPP, 1: JPSS-1, A: Aqua, T: Terra

                     val brightness: Temperature, // pixel temp in K
                     val brightness1: Double, // secondary channel pixel temp in K
                     val frp: Power, // pixel-integrated fire radiated power in MW
                     val scan: Double, // actual pixel size factor
                     val track: Double,
                     val confidence: Int,
                     val version: String,
                     val isDay: Boolean,

                     //-- fixed fields
                     val sensor: String = "VIIRS",
                     val pixelSize: Length = Meters(375) // at nadir
                   ) extends Hotspot {

  override def temp: Temperature = brightness

  override def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeDateTimeMember(DATE,date)
      .writeDoubleMember(LAT, position.latDeg,FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg,FMT_3_5)
      .writeDoubleMember(TEMP, temp.toKelvin,FMT_3_1)
      .writeDoubleMember(FRP, frp.toMegaWatt,FMT_1_1)
      .writeStringMember(SOURCE,source)
      .writeIntMember(CONF, confidence)
      .writeBooleanMember(DAY, isDay)
      .writeIntMember(SIZE, pixelSize.toMeters.toInt)
  }
}

case class ViirsHotspots (date: DateTime, src: String, elems: Array[ViirsHotspot]) extends Hotspots[ViirsHotspot]

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

  def parseHotspot(): ResultValue[ViirsHotspot] = {
    val lat: Angle = Degrees(readNextValue().toDouble)
    val lon: Angle = Degrees(readNextValue().toDouble)
    val brightness = Kelvin(readNextValue().toDouble)
    val scan = readNextValue().toDouble
    val track = readNextValue().toDouble
    val date = readDate()
    val src = readNextValue().intern
    skip(1) // we know the instrument
    val confidence = readConfidence()
    val version = readNextValue().intern // do we care?
    val brightness1 = readNextValue().toDouble
    val frp = MegaWatt(readNextValue().toDouble)
    val isDay = readDayNight()
    skipToNextRecord() // we don't care about daynight

    if (lat.isUndefined || lon.isUndefined) return Failure("no position")
    if (date.isUndefined) return Failure("no date")
    if (confidence < 0) return Failure("unknown confidence")
    SuccessValue( new ViirsHotspot(date,GeoPosition(lat,lon),src,brightness,brightness1,frp,scan,track,confidence,version, isDay))
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
class ViirsHotspotArchiveReader (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int,
                                 geoFilter: Option[GeoPositionFilter] = None)
  extends StreamArchiveReader with ViirsHotspotParser {
  def this(conf: Config) = this( createInputStream(conf),
    configuredPathName(conf),
    conf.getIntOrElse("buffer-size",4096),
    GeoPositionFilter.fromOptionalConfig(conf, "bounds"))

  val lineBuffer = new LineBuffer(iStream,bufLen)
  val hs = ArrayBuffer.empty[ViirsHotspot]

  protected var prev: ViirsHotspot = null
  protected var date = DateTime.UndefinedDateTime

  lineBuffer.nextLine() // skip the header line of the archive

  override def readNextEntry(): Option[ArchiveEntry] = {
    var src: String = ""
    hs.clear()

    def result: Option[ArchiveEntry] = if (hs.isEmpty) None else archiveEntry(date, ViirsHotspots(date, src, hs.toArray))

    if (prev != null) { // we have a leftover from our previous invocation
      date = prev.date
      hs += prev
      prev = null
    }

    while (lineBuffer.nextLine()) {
      if (initialize(lineBuffer)) {
        parseHotspot() match {
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
