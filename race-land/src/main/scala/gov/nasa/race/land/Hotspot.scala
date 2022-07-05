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

import gov.nasa.race.Dated
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{AssocSeq, JsonSerializable, JsonWriter}
import gov.nasa.race.geo.GeoPositioned
import gov.nasa.race.uom.{DateTime, Length, Power, Temperature}

import java.text.DecimalFormat

object Hotspot {
  //--- constants
  val LOW_CONFIDENCE = 0
  val NOMINAL_CONFIDENCE = 1
  val HIGH_CONFIDENCE = 2

  //--- lexical constants
  val DATE = asc("date")
  val LAT = asc("lat")
  val LON = asc("lon")
  val TEMP = asc("temp")
  val FRP = asc("frp")
  val SOURCE = asc("src")
  val SENSOR = asc("sensor")
  val CONF = asc("conf")
  val SIZE = asc("size")
  val DAY = asc("day")

  val FMT_3_1 = new DecimalFormat("###.#")
  val FMT_1_1 = new DecimalFormat("#.#")
  val FMT_3_5 = new DecimalFormat("###.#####")
}

/**
  * abstract type of fire detection observations
  */
trait Hotspot extends Dated with GeoPositioned with JsonSerializable {
  // means we have a date and a position

  // general hotspot data
  def source: String  // satellite name, ground station etc.
  def sensor: String  // sensor that produced the hotspot (VIIRS, MODIS, ABI etc.)

  def temp: Temperature  // fire pixel brightness
  def frp: Power         // fire radiative power
  def pixelSize: Length  // rough estimate of resolution
  //.. more to follow
}

/**
  * a match type root for a fixed sequence of Hotspot objects
  * note that timestamps in the included hotspots do not need to be monotonic (some FIRMS archives have backjumps)
  */
trait Hotspots [T <: Hotspot] extends Seq[T] with JsonSerializable with Dated {
  val date: DateTime
  val src: String
  val elems: Array[T]

  def serializeMembersTo (writer: JsonWriter): Unit = {
    writer.beginArray
    foreach( _.serializeTo(writer))
    writer.endArray
  }

  override def apply(i: Int): T = elems(i)
  override def length: Int = elems.length
  override def iterator: Iterator[T] = elems.iterator

  override def toString: String = elems.mkString("[\n  ", ",\n  ", "\n]")
}


