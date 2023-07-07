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
package gov.nasa.race.earth.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.FileReplayActor
import gov.nasa.race.geo.BoundingBoxGeoFilter
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Time._

import java.io.File
import java.nio.file.{Path, PathMatcher}
import HrrrFile.hrrrRE


object HrrrPathMatcher extends PathMatcher {
  def matches (path: Path): Boolean = hrrrRE.matches(path.getFileName.toString)
}

/**
 * actor that replays HRRR files from configured directory
 * file names follow the convention:
 *
 *   hrrr-{type}-{region}-wrfsfcf-YYYYMMDD-tHHz-HH.grib2
 * eg.
 *    hrrr-tuvc-west-wrfsfcf-20200820-t00z-00.grib2
 */
class HrrrReplayActor (val config: Config) extends FileReplayActor[HrrrFileAvailable] {

  val hrrrType = config.getString("hrrr-type")
  val area = config.getString("area")
  val bounds = BoundingBoxGeoFilter.fromConfig( config.getConfig("bounds"))

  override protected def getPathMatcher(): PathMatcher = HrrrPathMatcher

  override protected def getFileAvailable (f: File) : Option[HrrrFileAvailable] = {
    f.getName match {
      case hrrrRE(ht,ar, yr, mon, day, hr, fc) =>
        if (ht == hrrrType && ar == area) {
          val baseDate = DateTime(yr.toInt, mon.toInt, day.toInt, hr.toInt, 0, 0, 0, DateTime.utcId)
          val forecastDate = baseDate + Hours(fc.toInt)
          Some( HrrrFileAvailable(hrrrType, area, f, baseDate, forecastDate, bounds))
        } else None

      case _ => None
    }
  }

  override protected def isFileAvailableEarlier (a: HrrrFileAvailable, b: HrrrFileAvailable): Boolean = {
    if (a.baseDate < b.baseDate) true
    else if (a.baseDate == b.baseDate) (a.forecastDate < b.forecastDate)
    else false
  }

  override protected def publishFileAvailable(fe: HrrrFileAvailable): Unit = publish(fe)
}
