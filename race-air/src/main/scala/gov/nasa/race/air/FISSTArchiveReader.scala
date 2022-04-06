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
package gov.nasa.race.air

import com.typesafe.config.Config
import gov.nasa.race.archive.LineBufferArchiveReader
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.Utf8CsvPullParser
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.uom.{DateTime, Speed}
import gov.nasa.race.{Failure, ResultValue, SuccessValue}

import java.io.InputStream

/**
  * archive reader for FISST archives, which are CSV files with the following structure:
  *
  * date,lat(deg),lon(deg),alt(ft),FL,selectedAlt(ft),cs,selectedHeading(deg)
  * 2020-02-01T00:29:06.836Z, 17.02070849, -56.69126797, 41100, 390, 39000, Aircraft X, 44.296875
  * ...
  */
class FISSTArchiveReader (iStream: InputStream, pathName: String="<unknown>", bufLen: Int)
                               extends LineBufferArchiveReader[FlightPos](iStream,pathName,bufLen) with Utf8CsvPullParser {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))

  lineBuffer.nextLine() // the first line is a header line

  def parseLine() : ResultValue[FlightPos] = {
    try {
      val date = DateTime.parseISO(readNextValue())
      val lat = Degrees(readNextValue().toDouble)
      val lon = Degrees(readNextValue().toDouble)
      val alt = Feet(readNextValue().toInt)
      skip(2)
      //val fl = readNextValue().toDouble
      //val selAlt = Feet(readNextValue().toInt)
      val id = readNextValue().intern
      val selHdg = Degrees(readNextValue().toDouble)

      SuccessValue( new FlightPos(id,id,GeoPosition(lat,lon,alt),Speed.UndefinedSpeed,selHdg,Speed.UndefinedSpeed,date))

    } catch { // basic CSV parse error, likely corrupted data
      case x: Throwable => Failure(x.getMessage)
    }
  }
}
