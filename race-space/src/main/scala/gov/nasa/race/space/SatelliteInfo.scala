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
package gov.nasa.race.space

import com.typesafe.config.Config
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.GeoPosition

object SatelliteInfo {

  val satelliteInfos: Map[Int,SatelliteInfo] = Map(
    (27424 -> new SatelliteInfo(27424, "Aquqa", "polar orbiting MODIS satellite")),
    (25994 -> new SatelliteInfo(25994, "Terra", "polar orbiting MODIS satellite")),
    (43013 -> new SatelliteInfo(43013, "J-1", "polar orbiting VIIRS satellite")),
    (37849 -> new SatelliteInfo(37849, "NPP", "polar orbiting VIIRS satellite")),
    (43226 -> new SatelliteInfo(43226, "G17", "geostationary ABI satellite (GOES-West)")),
    (41866 -> new SatelliteInfo(41866, "G16", "geostationary ABI satellite (GOES-East)"))
  )
}

/**
 * generic satellite info, normally initialized from config files
 */
class SatelliteInfo (
                      val satId: Int,     // unique NORAD catalog id
                      val name: String,
                      val description: String,
                      val show: Boolean = true
                    ) extends JsonSerializable {

  def this (conf: Config) = this(
    conf.getInt("sat-id"),
    conf.getString("name"),
    conf.getString("description"),
    conf.getBooleanOrElse("show", true)
  )

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer
      .writeIntMember("satId", satId)
      .writeStringMember("name", name)
      .writeStringMember("description", description)
      .writeBooleanMember("show", show)
  }
}
