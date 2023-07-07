/*
 * Copyright (c) 2023, United States Government, as represented by the
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

import gov.nasa.race.common.FileAvailable
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.{Angle, DateTime, Speed, Temperature}

import java.io.File

case class WxStation(id: String, name: String, pos: GeoPosition, updateMinute: Int)

object WxStation {
  def sortByMinute(a: WxStation, b: WxStation): Boolean = {
    if (a.updateMinute < b.updateMinute) true
    else if (a.updateMinute == b.updateMinute) a.id < b.id
    else false
  }
}

case class WxStationAvailable (station: WxStation, file: File, date: DateTime) extends FileAvailable

case class BasicWxStationRecord (date: DateTime, temp: Temperature, windSpeed: Speed, windDirection: Angle)