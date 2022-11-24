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
import gov.nasa.race.{Dated, Failure, ResultValue, SuccessValue, ifSome}
import gov.nasa.race.archive.{ArchiveEntry, StreamArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{AssocSeq, JsonSerializable, JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.{GeoPosition, GeoPositionFilter, GeoPositioned}
import gov.nasa.race.uom.{DateTime, Length, Power, Temperature}

import java.io.InputStream
import java.text.DecimalFormat
import scala.collection.mutable.ArrayBuffer

object Hotspot {
  //--- constants
  val LOW_CONFIDENCE = 0
  val NOMINAL_CONFIDENCE = 1
  val HIGH_CONFIDENCE = 2

  //--- lexical constants
  val SAT_ID = asc("satId")
  val DATE = asc("date")
  val LAT = asc("lat")
  val LON = asc("lon")
  val BRIGHT = asc("bright")
  val FRP = asc("frp")
  val AREA = asc("area")
  val BOUNDS = asc("bounds")
  val SOURCE = asc("src")
  val VERSION = asc("version")
  val SENSOR = asc("sensor")
  val CONF = asc("conf")
  val SCAN = asc("scan")
  val TRACK = asc("track")
  val SIZE = asc("size")
  val DAY = asc("day")
  val HOTSPOTS = asc("hotspots")
  val HISTORY = asc("history")
}
import Hotspot._

/**
  * abstract type of fire detection observations
  */
trait Hotspot extends AnyRef with Dated with GeoPositioned with JsonSerializable {
  // means we have a date and a position

  // general hotspot data
  def satId: Int      // unique satellite identifier (usually NORAD cat id)
  def source: String  // satellite name, ground station etc.
  def sensor: String  // sensor that produced the hotspot (VIIRS, MODIS, ABI etc.)

  def bright: Temperature  // fire pixel brightness
  def frp: Power         // fire radiative power

  // data quality / confidence classification (TODO - should we extend this?)
  def isGoodPixel: Boolean
  def isProbablePixel: Boolean

  def bounds: Seq[GeoPosition]
  //.. more to follow

  def serializeBoundsTo(writer: JsonWriter): Unit = {
    bounds.foreach { p =>
      writer.writeArray { w=>
        w.writeDouble(p.latDeg, "%.5f")
        w.writeDouble(p.lonDeg, "%.5f")
      }
    }
  }
}

/**
  * a match type root for a fixed sequence of Hotspot objects
  * note that timestamps in the included hotspots do not need to be monotonic (some FIRMS archives have backjumps)
  */
trait Hotspots [T <: Hotspot] extends Seq[T] with JsonSerializable with Dated {
  val satId: Int
  val date: DateTime
  val src: String
  val data: Seq[T]

  def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeIntMember(SAT_ID, satId)
      .writeStringMember(SOURCE, src)
      .writeDateTimeMember(DATE, date)
      .writeArrayMember(HOTSPOTS){ w=> data.foreach( _.serializeTo(w)) }
  }

  override def apply(i: Int): T = data(i)
  override def length: Int = data.length
  override def iterator: Iterator[T] = data.iterator

  override def toString: String = data.mkString("[\n  ", ",\n  ", "\n]")
}

/**
 * ArchiveReader root type that reads CSV archives containing a sequence of hotspot records that should be reported
 * as batched Hotspots collections
 */
abstract class HotspotArchiveReader[T<:Hotspot] (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int,
                                 geoFilter: Option[GeoPositionFilter] = None) extends StreamArchiveReader with Utf8CsvPullParser {

  def this(conf: Config) = this( createInputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096),
    GeoPositionFilter.fromOptionalConfig(conf, "bounds"))

  val lineBuffer = new LineBuffer(iStream,bufLen)
  val hs = ArrayBuffer.empty[T]

  protected var prev: Option[T] = None
  protected var date = DateTime.UndefinedDateTime
  protected var src = ""

  lineBuffer.nextLine() // skip the header line of the archive

  def parseHotspot(): ResultValue[T]
  def batchProduct: Option[Hotspots[T]]

  override def readNextEntry(): Option[ArchiveEntry] = {
    src = ""
    hs.clear()

    def result: Option[ArchiveEntry] = batchProduct.flatMap( hs=> archiveEntry(date,hs))

    ifSome(prev) { h =>
      date = h.date
      hs += h
      prev = None
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
                  prev = Some(hotspot)
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
