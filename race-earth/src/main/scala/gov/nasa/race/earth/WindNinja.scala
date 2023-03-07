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

import gov.nasa.race.common.ExternalProc
import gov.nasa.race.uom.{DateTime, Length}
import gov.nasa.race.util.FileUtils

import java.io.File

/**
 * external proc to run a single WindNinja step on an HRRR forecast file, returning the HUVW JSON file created by WindNinja
 *
 * output path, DEM file and mesh resolution are constant
 * HRRR file and respective forecast time change between executions
 * result is the HUVW grid in JSON notation
 */
class WindNinjaHrrrSingleRun(val prog: File, val dataPath: File, val outputPath: File, val demFile: File, val meshResolution: Length, val windHeight: Length) extends ExternalProc[File] {

  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!FileUtils.ensureWritableDir(outputPath).isDefined) throw new RuntimeException(s"cannot create WindNinja output dir $outputPath")
  if (!demFile.isFile) throw new RuntimeException(s"DEM file not found: $demFile")
  if (!dataPath.isDirectory) throw new RuntimeException(s"WINDNINJA_DATA path not found: $dataPath")

  override def env = Seq( ("WINDNINJA_DATA" -> dataPath.getPath))

  val vegetationType = "trees" // TBD - from demFile bounds
  val timeZone = "America/Los_Angeles" // TBD - from system

  protected var hrrrForecast: Option[File] = None
  protected var forecastDate: DateTime = DateTime.UndefinedDateTime
  protected var ymdh = (0,0,0,0)

  def setHrrrForecast( hrrrFile: File, date: DateTime): this.type = {
    if (!hrrrFile.isFile) throw new RuntimeException(s"HRRR forecast file not found: $hrrrFile")
    hrrrForecast = Some(hrrrFile)
    forecastDate = date
    ymdh = (date.getYear, date.getMonthValue, date.getDayOfMonth, date.getHour)
    this
  }

  override def buildCommand: String = {
    if (!hrrrForecast.isDefined) throw new RuntimeException("no HRRR forecast set")

    args = Seq(
      s"--output_path ${outputPath.getPath}",
      s"--num_threads 1",
      s"--initialization_method wxModelInitialization",
      s"--mesh_resolution ${meshResolution.toMeters}",
      s"--units_mesh_resolution m",
      s"--diurnal_winds true",
      s"--elevation_file ${demFile.getPath}",
      s"--vegetation $vegetationType",
      s"--output_wind_height ${windHeight.toMeters}",
      s"--units_output_wind_height m",
      s"--time_zone ${timeZone}",

      s"--forecast_filename ${hrrrForecast.get}",
      f"--forecast_time ${ymdh._1}%4d${ymdh._2}%02d${ymdh._3}%02dT${ymdh._4}%02d0000", // WN does not accept time zone (assumes UTC)
      s"--start_year ${ymdh._1}",
      s"--start_month ${ymdh._2}",
      s"--start_day ${ymdh._3}",
      s"--start_hour ${ymdh._4}",
      s"--stop_year ${ymdh._1}",
      s"--stop_month ${ymdh._2}",
      s"--stop_day ${ymdh._3}",
      s"--stop_hour ${ymdh._4}",

      s"--write_json true",
      s"--json_out_4326 true"
    )
    super.buildCommand
  }

  override def getSuccessValue: File = {
    // output/czu_utm_08-20-2020_0000_250m.json
    val fileName = f"${FileUtils.getBaseName(demFile)}_${ymdh._2}%02d-${ymdh._3}%02d-${ymdh._1}%4d_${ymdh._4}%02d00_${meshResolution.toMeters.round}m.json"
    val file = new File(outputPath, fileName)
    if (!file.isFile) throw new RuntimeException(s"output file not found: $file")
    file
  }
}