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

import gov.nasa.race.Dated
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{AssocSeq, JsonSerializable, JsonWriter}
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.uom.{DateTime, Length, Power, Temperature}

import java.text.DecimalFormat

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
  val TEMP = asc("temp")
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
trait Hotspot extends Dated with GeoPositioned with JsonSerializable {
  // means we have a date and a position

  // general hotspot data
  def satId: Int      // unique satellite identifier (usually NORAD cat id)
  def source: String  // satellite name, ground station etc.
  def sensor: String  // sensor that produced the hotspot (VIIRS, MODIS, ABI etc.)

  def temp: Temperature  // fire pixel brightness
  def frp: Power         // fire radiative power
  def pixelSize: Length  // rough estimate of resolution

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
  val elems: Seq[T]

  def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeIntMember(SAT_ID, satId)
      .writeStringMember(SOURCE, src)
      .writeDateTimeMember(DATE, date)
      .writeArrayMember(HOTSPOTS){ w=> elems.foreach( _.serializeTo(w)) }
  }

  override def apply(i: Int): T = elems(i)
  override def length: Int = elems.length
  override def iterator: Iterator[T] = elems.iterator

  override def toString: String = elems.mkString("[\n  ", ",\n  ", "\n]")
}


