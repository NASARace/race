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
import gov.nasa.race.geo.GeoPosition

import java.io.File
import scala.collection.mutable.ArrayBuffer

case class GeoPosData (pos: GeoPosition, value: Double)

/**
 * retrieval of limited number of data values based on gepgraphic position
 */
class Wgrib2RetrieveData (val prog: File) extends ExternalProc[Array[GeoPosData]] {

  protected var grib2File: Option[File] = None
  protected var matchSpec: Option[String] = None
  protected var queryPos: ArrayBuffer[GeoPosition] = ArrayBuffer.empty

  captureConsoleOutputToString()  // wgrib2 -lon prints results to stdout

  override def reset(): this.type = {
    queryPos.clear()
    this
  }

  override def canRun: Boolean = grib2File.isDefined && matchSpec.isDefined && queryPos.nonEmpty

  override def buildCommand: String = {
    args = Seq(
      grib2File.get.getPath,
      s"""-match "${matchSpec.get}""""
    ) ++ queryPos.map( p=> s"-lon ${p.lonDeg} ${p.latDeg}")

    super.buildCommand
  }

  /*
   * for a command like
   *     wgrib2 hrrr-20230411-17.grib2 -match ":(TCDC):(entire atmosphere).*:" -lon -122 37 -lon -121 36
   * output is in the form of
   *     4:2056664:lon=237.997712,lat=36.985828,val=100:lon=238.985298,lat=35.997789,val=86
   */

  val resRE = ":lon=([^,]+),lat=([^,]+),val=([^:]+)".r

  override def getSuccessValue: Array[GeoPosData] = {
    resRE.findAllMatchIn(outputBuffer.toString).toArray.map { m=>
      val pos = GeoPosition.fromDegrees(m.group(1).toDouble,m.group(2).toDouble).normalized // grib2 does not use negative lon
      val data = m.group(3).toDouble
      GeoPosData(pos,data)
    }
  }

  //--- arg setters

  def setGrib2 (grib2: File): this.type = {
    grib2File = Some(grib2)
    this
  }

  def setRecordMatch (regex: String): this.type = {
    matchSpec = Some(regex)
    this
  }

  def addQueryPos (pos: GeoPosition): this.type = {
    queryPos += pos
    this
  }
}
