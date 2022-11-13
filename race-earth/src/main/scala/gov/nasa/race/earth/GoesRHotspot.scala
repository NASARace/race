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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{FMT_1_1, FMT_3_1, FMT_3_5, JsonWriter}
import gov.nasa.race.geo.{GeoMap, GeoPosition}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.{Area, DateTime, Length, Power, Temperature, Time}
import gov.nasa.race.earth.Hotspot._

object GoesRHotspot {
  val MASK = asc("mask")
  val N_TOTAL = asc("nTotal")
  val N_GOOD = asc("nGood")
  val N_PROBABLE = asc("nProbable")

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
}
import GoesRHotspot._

/**
  * class representing a potential fire pixel as reported by GOES-R ABI L2 Fire (Hot Spot Characterization) data product
  * see https://www.goes-r.gov/products/docs/PUG-L2+-vol5.pdf
  */
case class GoesRHotspot (
                          satId: Int,               // NORAD cat id
                          date: DateTime,
                          position: GeoPosition,    // center point
                          dqf: Int,                 // data quality flag
                          mask: Int,                // mask flag
                          temp: Temperature,        // pixel temp in K
                          frp: Power,               // pixel-integrated fire radiated power in MW
                          area: Area,               // fire area in m^2
                          bounds: Seq[GeoPosition], // pixel boundaries polygon
                          source: String,

                          //--- fixed
                          sensor: String = "ABI",
                          pixelSize: Length = Meters(2000),
                        ) extends Hotspot {

  def this (satId: Int, date: DateTime, pix: GoesRPixel, src: String) = {
    this(satId, date, pix.center, pix.dqf, pix.mask, pix.temp, pix.frp, pix.area, pix.bounds.toSeq, src)
  }

  //--- pixel classification
  def hasValues: Boolean = temp.isDefined && area.isDefined && frp.isDefined // correlates with isGoodPixel
  def hasSomeValues: Boolean = temp.isDefined || area.isDefined || frp.isDefined // correlates with isHighProbabilityPixel or isMediumProbabilityPixel

  def isGoodPixel: Boolean = (mask == MASK_GOOD) || (mask == MASK_TEMP_GOOD)
  def isProbablePixel: Boolean = isHighProbabilityPixel || isMediumProbabilityPixel
  def isHighProbabilityPixel: Boolean = (mask == MASK_HIGH_PROB) || (mask == MASK_TEMP_HIGH_PROB)
  def isMediumProbabilityPixel: Boolean = (mask == MASK_MED_PROB) || (mask == MASK_TEMP_MED_PROB)

  override def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeIntMember(SAT_ID, satId)
      .writeDateTimeMember(DATE,date)
      .writeDoubleMember(LAT, position.latDeg,FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg,FMT_3_5)
      .writeDoubleMember(TEMP, temp.toKelvin,FMT_3_1)
      .writeDoubleMember(FRP, frp.toMegaWatt,FMT_1_1)
      .writeLongMember(AREA, area.toSquareMeters.round)
      .writeIntMember(MASK, mask)
      .writeStringMember(SOURCE,source)
      .writeArrayMember(BOUNDS){serializeBoundsTo}
      .writeIntMember(SIZE, pixelSize.toRoundedMeters.toInt)
  }


  def _temp: String = if (temp.isUndefined) "      " else f"${temp.toKelvin}%6.1f"
  def _frp: String = if (frp.isUndefined)   "      " else f"${frp.toMegaWatt}%6.2f"
  def _area: String = if (area.isUndefined) "         " else f"${area.toSquareMeters}%8.0f}"
  def _pos: String = f"{ ${position.latDeg}%+9.5f, ${position.lonDeg}%+10.5f }"
  override def toString: String = s"{src: $source, date: $date, pos: ${_pos}, temp: ${_temp}, frp: ${_frp}, area: ${_area}, mask: $mask"
}

/**
  * match-able collection
  */
case class GoesRHotspots (date: DateTime, satId: Int, src: String, elems: Seq[GoesRHotspot]) extends Hotspots[GoesRHotspot]
