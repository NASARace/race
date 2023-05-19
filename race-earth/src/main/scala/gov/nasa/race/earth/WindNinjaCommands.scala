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

import gov.nasa.race.allDefined
import gov.nasa.race.common.ExternalProc
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.{DateTime, Length}
import gov.nasa.race.util.FileUtils

import java.io.File

object VegetationType {
  def of (name: String): Option[VegetationType] =
    name match {
      case "grass" | "Grass" => Some(GrassVegetation)
      case "brush" | "Brush" | "bush" => Some(BrushVegetation)
      case "tree" | "trees" => Some(TreeVegetation)
    }
}
sealed abstract class VegetationType (val name: String) {
  override def toString(): String = name
}
object GrassVegetation extends VegetationType("grass")
object BrushVegetation extends VegetationType ("brush")
object TreeVegetation extends VegetationType ("trees")

object WindNinjaDefaults {
  val windHeight: Length = Meters(10)
  val meshResolution: Length = Meters(250)
}

case class WindNinjaWxModelResults (huvw: File, huvw0: File)

/**
 * external proc to run a single WindNinja step on an HRRR forecast file, returning the HUVW JSON file created by WindNinja
 *
 * output path, DEM file and mesh resolution are constant
 * HRRR file and respective forecast time change between executions
 * result is the HUVW grid in JSON notation
 *
 * this is not a typical ExternalProc since it relies on cached values to create the highly structured command and
 * to compute result values
 */
class WindNinjaSingleRun(val prog: File, val outputPath: File) extends ExternalProc[WindNinjaWxModelResults] {
  if (!prog.isFile || !prog.canExecute) throw new RuntimeException(s"WindNinja executable not found: $prog")
  if (!FileUtils.ensureWritableDir(outputPath).isDefined) throw new RuntimeException(s"cannot create WindNinja output dir $outputPath")

  //override def env = Seq( ("WINDNINJA_DATA" -> dataPath.getPath))

  val timeZone = DateTime.localId

  protected var wxModelFile: Option[File] = None
  protected var wxStationFile: Option[File] = None
  protected var vegetationType: Option[VegetationType] = None

  // those we can set but keep between runs
  protected var demFile: Option[File] = None
  protected var windHeight: Length = WindNinjaDefaults.windHeight
  protected var meshResolution: Length = WindNinjaDefaults.meshResolution

  protected var forecastDate: DateTime = DateTime.UndefinedDateTime
  protected var ymdt = (0,0,0, 0,0,0,0)

  override def reset(): this.type = {
    super.reset()
    wxModelFile = None
    forecastDate = DateTime.UndefinedDateTime
    //demFile = None
    //windHeight =  WindNinjaDefaults.windHeight
    //meshResolution = WindNinjaDefaults.meshResolution
    this
  }

  override def canRun: Boolean = {
    (wxModelFile.isDefined || wxStationFile.isDefined) && demFile.isDefined
  }

  //--- argument setters

  def setHrrrForecast( hrrrFile: File, date: DateTime): this.type = {
    if (!hrrrFile.isFile) throw new RuntimeException(s"HRRR forecast file not found: $hrrrFile")
    wxModelFile = Some(hrrrFile)
    forecastDate = date
    ymdt = date.getYMDT
    this
  }

  def setStationFile(stationFile: File): this.type = {
    if (!stationFile.isFile) throw new RuntimeException(s"weather station file not found: $stationFile")
    wxStationFile = Some(stationFile)
    this
  }

  def setVegetationType (vt: VegetationType): this.type = {
    vegetationType = Some(vt)
    this
  }

  def setMeshResolution (mr: Length): this.type = {
    meshResolution = mr
    this
  }

  def setWindHeight (wh: Length): this.type = {
    windHeight = wh
    this
  }

  def setDemFile(df: File): this.type = {
    if (!df.isFile) throw new RuntimeException(s"DEM file not found: $df")
    demFile = Some(df)
    this
  }

  //--- command assembly

  override def buildCommand: StringBuilder = {
    appendInitialization( super.buildCommand
      .append(s" --output_path ${outputPath.getPath}")

      .append( " --num_threads 1")
      .append(s" --mesh_resolution ${meshResolution.toMeters}")
      .append( " --units_mesh_resolution m")
      .append( " --diurnal_winds true")
      .append(s" --elevation_file ${demFile.get.getPath}")
      .append(s" --vegetation ${vegetationType.get}")
      .append(s" --output_wind_height ${windHeight.toMeters}")
      .append( " --units_output_wind_height m")
      .append(s" --time_zone ${timeZone}")

      .append( " --write_goog_output false")
      .append( " --write_shapefile_output false")
      .append( " --write_pdf_output false")
      .append( " --write_farsite_atm false")
      .append( " --write_wx_model_goog_output false")
      .append( " --write_wx_model_shapefile_output false")
      .append( " --write_wx_model_ascii_output false")
      .append( " --write_wx_station_kml false")

      .append( " --write_huvw_output true")
      .append( " --write_huvw_0_output true")
    )
  }

  def appendInitialization (sb: StringBuilder): StringBuilder = {
    if (wxModelFile.isDefined) {
      sb
        .append( " --initialization_method wxModelInitialization")
        .append(s" --forecast_filename ${wxModelFile.get}")
        .append(f" --forecast_time ${ymdt._1}%4d${ymdt._2}%02d${ymdt._3}%02dT${ymdt._4}%02d0000") // WN does not accept time zone (assumes UTC)
        .append(s" --start_year ${ymdt._1}")
        .append(s" --start_month ${ymdt._2}")
        .append(s" --start_day ${ymdt._3}")
        .append(s" --start_hour ${ymdt._4}")
        .append(s" --stop_year ${ymdt._1}")
        .append(s" --stop_month ${ymdt._2}")
        .append(s" --stop_day ${ymdt._3}")
        .append(s" --stop_hour ${ymdt._4}")

    } else if (wxStationFile.isDefined) {
      sb
        .append( " --initialization_method pointInitialization")
        .append( " --match_points true")
        .append(s" --wx_station_filename ${wxStationFile.get}")

    } else throw new RuntimeException("no suitable initialization data")
  }

  //--- result value computation

  override def getSuccessValue: WindNinjaWxModelResults = {
    // output/czu_utm_08-20-2020_0000_250m_huvw.tif
    val baseName = f"${FileUtils.getBaseName(demFile.get)}_${ymdt._2}%02d-${ymdt._3}%02d-${ymdt._1}%4d_${ymdt._4}%02d00_${meshResolution.toMeters.round}m_"

    val huvwFile = new File(outputPath, baseName + "huvw.tif")
    if (!huvwFile.isFile) throw new RuntimeException(s"windninja output file not found: $huvwFile")

    val huvw0File = new File(outputPath, baseName + "huvw_0.tif")
    if (!huvw0File.isFile) throw new RuntimeException(s"windninja output file not found: $huvw0File")

    WindNinjaWxModelResults(huvwFile,huvw0File)
  }
}