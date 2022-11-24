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
import gov.nasa.race.{Failure, ResultValue, SuccessValue}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{FMT_1_1, FMT_3_1, FMT_3_5, JsonSerializable, JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.{GeoMap, GeoPosition, GeoPositionFilter}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.{Area, DateTime, Length, Power, Temperature, Time}
import gov.nasa.race.earth.Hotspot._
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Area.SquareMeters
import gov.nasa.race.uom.Power.MegaWatt
import gov.nasa.race.uom.Temperature.Kelvin

import java.io.{InputStream, PrintStream}
import java.lang.Math.abs
import scala.collection.mutable.ArrayBuffer

object GoesrHotspot {
  val DQF = asc("dqf")
  val MASK = asc("mask")
  val GOOD = asc("good")
  val TEMP_FILTERED = asc("tempFiltered")
  val PROB = asc("probability")
  val N_TOTAL = asc("nTotal")
  val N_GOOD = asc("nGood")
  val N_HIGH = asc("nHigh")
  val N_MEDIUM = asc("nMedium")
  val N_LOW = asc("nLow")
  val N_PROBABLE = asc("nProbable")
  val CENTER = asc("center")

  // data quality flag, see see https://www.goes-r.gov/products/docs/PUG-L2+-vol5.pdf pg.494pp
  val DQF_UNKNOWN = -1
  val DQF_GOOD_FIRE = 0          // good_quality_fire_pixel_qf
  val DQF_GOOD_FIRE_FREE = 1     // good_quality_fire_free_land_pixel_qf ?
  val DQF_INVALID_CLOUD = 2      // invalid_due_to_opaque_cloud_pixel_qf
  val DQF_INVALID_MISC = 3       // invalid_due_to_surface_type_or_sunglint_or_LZA_threshold_exceeded_or_off_earth_or_missing_input_data_qf
  val DQF_INVALID_INPUT = 4      // invalid_due_to_bad_input_data_qf
  val DWF_INVALID_ALG = 5        // invalid_due_to_algorithm_failure_qf

  // mask values for fire pixels, see https://www.goes-r.gov/products/docs/PUG-L2+-vol5.pdf pg.493pp
  val MASK_GOOD = 10             // good_fire_pixel
  val MASK_SATURATED = 11        // saturated_fire_pixel
  val MASK_CLOUD_CONTAMINATED = 12 // cloud_contaminated_fire_pixel
  val MASK_HIGH_PROB = 13        // high_probability_fire_pixel
  val MASK_MED_PROB = 14         // medium_probability_fire_pixel
  val MASK_LOW_PROB = 15         // low_probability_fire_pixel
  val MASK_TEMP_GOOD = 30        // temporally_filtered_good_fire_pixel
  val MASK_TEMP_SATURATED = 31   // temporally_filtered_saturated_fire_pixel
  val MASK_TEMP_COULD_CONTAMINATED = 32 // temporally_filtered_cloud_contaminated_fire_pixel
  val MASK_TEMP_HIGH_PROB = 33   // temporally_filtered_high_probability_fire_pixel
  val MASK_TEMP_MED_PROB = 34    // temporally_filtered_medium_probability_fire_pixel
  val MASK_TEMP_LOW_PROB = 35    // temporally_filtered_low_probability_fire_pixel

  def isValidFirePixel (mask: Int): Boolean = mask >= 10 && mask <= 35

  def printCsvHeaderTo (ps: PrintStream): Unit = {
    ps.println("SAT-ID,EPOCH-MILLIS,LAT,LON,TEMP,FRP,AREA,DQF,MASK,SRC,BOUNDS")
  }
}
import GoesrHotspot._

/**
  * class representing a potential fire pixel as reported by GOES-R ABI L2 Fire (Hot Spot Characterization) data product
  * see https://www.goes-r.gov/products/docs/PUG-L2+-vol5.pdf
  */
case class GoesrHotspot(
                         satId: Int, // NORAD cat id
                         date: DateTime,
                         position: GeoPosition, // center point
                         dqf: Int, // data quality flag
                         mask: Int, // mask flag
                         bright: Temperature, // pixel temp in K
                         frp: Power, // pixel-integrated fire radiated power in MW
                         area: Area, // fire area in m^2
                         source: String,
                         bounds: Seq[GeoPosition], // pixel boundaries polygon

                         //--- fixed
                         sensor: String = "ABI",
                         pixelSize: Length = Meters(2000),
                        ) extends Hotspot {

  def this (satId: Int, date: DateTime, pix: GoesRPixel, src: String) = {
    this(satId, date, pix.center, pix.dqf, pix.mask, pix.temp, pix.frp, pix.area, src, pix.bounds.toSeq)
  }

  // this key can be used to sort, cluster or match hotspots in local areas (not crossing equator or Greenwich meridian)
  // note that 4 digits (degrees) give us about 10m accuracy at the equator
  // TODO - use UTM or MGRS
  val center: Long = ((abs(position.latDeg) * 10000).round)<<32 | (abs(position.lonDeg) * 10000).round
  //(abs(position.latDeg) * 10000).round + (abs(position.lonDeg) * 10000).round

  //--- pixel classification
  def hasValues: Boolean = bright.isDefined && area.isDefined && frp.isDefined // correlates with isGoodPixel
  def hasSomeValues: Boolean = bright.isDefined || area.isDefined || frp.isDefined // correlates with isHighProbabilityPixel or isMediumProbabilityPixel

  def isGoodPixel: Boolean = (mask == MASK_GOOD) || (mask == MASK_TEMP_GOOD)
  def isProbablePixel: Boolean = isHighProbabilityPixel || isMediumProbabilityPixel
  def isHighProbabilityPixel: Boolean = (mask == MASK_HIGH_PROB) || (mask == MASK_TEMP_HIGH_PROB)
  def isMediumProbabilityPixel: Boolean = (mask == MASK_MED_PROB) || (mask == MASK_TEMP_MED_PROB)
  def isLowProbabilityPixel: Boolean = (mask == MASK_LOW_PROB) || (mask == MASK_TEMP_LOW_PROB)

  def pixelProbability: String = mask match {
    case MASK_HIGH_PROB | MASK_TEMP_HIGH_PROB => "high"
    case MASK_MED_PROB | MASK_TEMP_MED_PROB => "medium"
    case MASK_LOW_PROB | MASK_TEMP_LOW_PROB => "low"
    case _ => "?"
  }
  def isTemporallyFiltered: Boolean = (mask == MASK_TEMP_GOOD) || (mask == MASK_TEMP_HIGH_PROB) || (mask == MASK_TEMP_MED_PROB) || (mask == MASK_TEMP_LOW_PROB)

  override def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeIntMember(SAT_ID, satId)
      .writeDateTimeMember(DATE,date)
      .writeDoubleMember(LAT, position.latDeg,FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg,FMT_3_5)
      .writeDoubleMember(BRIGHT, bright.toKelvin,FMT_3_1)
      .writeDoubleMember(FRP, frp.toMegaWatt,FMT_1_1)
      .writeLongMember(AREA, area.toSquareMeters.round)
      .writeIntMember(DQF, dqf)
      .writeIntMember(MASK, mask)
      .writeStringMember(SOURCE,source)

      // synthesized fields we add
      .writeBooleanMember(GOOD, isGoodPixel)
      .writeBooleanMember(TEMP_FILTERED, isTemporallyFiltered)
      .writeStringMember(PROB, pixelProbability)
      .writeLongMember(CENTER, center)

      .writeArrayMember(BOUNDS){serializeBoundsTo}
  }

  def printCsvTo (ps: PrintStream): Unit = {
    ps.print(s"$satId,")
    ps.print(s"${date.toEpochMillis},")
    ps.print(f"${position.latDeg}%.5f,")
    ps.print(f"${position.lonDeg}%.5f,")
    if (bright.isDefined) ps.print(f"${bright.toKelvin}%.1f,") else ps.print(',')
    if (frp.isDefined) ps.print(f"${frp.toMegaWatt}%.1f,") else ps.print(',')
    if (area.isDefined) ps.print(s"${area.toSquareMeters.round},") else ps.print(',')
    ps.print(s"$dqf,")
    ps.print(s"$mask,")
    ps.print(s"$source")
    bounds.foreach( pos=> ps.print(f",${pos.latDeg}%.5f,${pos.lonDeg}%.5f"))
    ps.println()
  }

  def _temp: String = if (bright.isUndefined) "      " else f"${bright.toKelvin}%6.1f"
  def _frp: String = if (frp.isUndefined)   "      " else f"${frp.toMegaWatt}%6.2f"
  def _area: String = if (area.isUndefined) "         " else f"${area.toSquareMeters}%8.0f}"
  def _pos: String = f"{ ${position.latDeg}%+9.5f, ${position.lonDeg}%+10.5f }"
  override def toString: String = s"{src: $source, date: $date, pos: ${_pos}, temp: ${_temp}, frp: ${_frp}, area: ${_area}, mask: $mask"
}

trait GoesrHotspotParser extends Utf8CsvPullParser {

  def parseHotspot(): ResultValue[GoesrHotspot] = {
    val satId = readNextValue().toInt
    val date = readNextValue().toDateTime
    val pos = GeoPosition.fromDegrees(readNextValue().toDouble, readNextValue().toDouble)
    val temp = Kelvin(readNextValue().toDoubleOrNaN)
    val frp = MegaWatt(readNextValue().toDoubleOrNaN)
    val area = SquareMeters(readNextValue().toDoubleOrNaN)
    val dqf = readNextValue().toInt
    val mask = readNextValue().toInt
    val src = readNextValue().toString

    val bounds = ArrayBuffer.empty[GeoPosition]
    while (hasMoreData) {
      bounds += GeoPosition.fromDegrees(readNextValue().toDouble, readNextValue().toDouble)
    }

    SuccessValue( GoesrHotspot(satId,date,pos,dqf,mask,temp,frp,area,src,bounds.toSeq))
  }
}

case class GoesrHotspotStats(nGood: Int, nHigh: Int, nMedium: Int, nLow: Int) extends JsonSerializable {
  override def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeIntMember(N_GOOD, nGood)
    w.writeIntMember(N_HIGH, nHigh)
    w.writeIntMember(N_MEDIUM, nMedium)
    w.writeIntMember( N_LOW, nLow)
  }
}

object GoesrHotspots {
  def sortData (data: Seq[GoesrHotspot]): Seq[GoesrHotspot] = {
    data.sortWith( (a,b) => a.center > b.center)
  }
}

/**
  * matchable, serializable time-stamped collection of GoesRHotspots from one satellite
  */
case class GoesrHotspots (date: DateTime, satId: Int, src: String, data: Seq[GoesrHotspot]) extends Hotspots[GoesrHotspot] {
  val stats = computeStats

  def computeStats: GoesrHotspotStats = {
    var nGood = 0
    var nHigh = 0
    var nMedium = 0
    var nLow = 0
    data.foreach { hs=>
      if (hs.isGoodPixel) nGood += 1

      if (hs.isHighProbabilityPixel) nHigh += 1
      else if (hs.isMediumProbabilityPixel) nMedium += 1
      else if (hs.isLowProbabilityPixel) nLow += 1
    }

    GoesrHotspotStats(nGood,nHigh,nMedium,nLow)
  }

  override def serializeMembersTo (w: JsonWriter): Unit = {
    super.serializeMembersTo(w)
    stats.serializeMembersTo(w)
  }
}

/**
 * ArchiveReader that works on CSV archives containing time-ordered sequences of GoesRHotspot records
 */
class GoesrHotspotArchiveReader (iStream: InputStream, pathName: String="<unknown>", bufLen: Int, geoFilter: Option[GeoPositionFilter] = None)
         extends HotspotArchiveReader[GoesrHotspot](iStream,pathName,bufLen,geoFilter) with GoesrHotspotParser {

  def this(conf: Config) = this( createInputStream(conf), configuredPathName(conf),
    conf.getIntOrElse("buffer-size",4096),
    GeoPositionFilter.fromOptionalConfig(conf, "bounds"))

  override def batchProduct: Option[Hotspots[GoesrHotspot]] = {
    if (hs.nonEmpty) Some(GoesrHotspots(date, hs.head.satId, hs.head.source, hs.toSeq)) else None
  }
}
