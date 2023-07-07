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

import gov.nasa.race.common.ByteCsvPullParser
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.{earth, repeat}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Speed.{MetersPerSecond, UsMilesPerHour}
import gov.nasa.race.uom.Temperature.{Celsius, Fahrenheit, Kelvin}
import gov.nasa.race.uom.{Angle, DateTime, Speed, Temperature}

/**
 * parse RAWS response data, which has the form:
 * # STATION: BNDC1
 * # STATION NAME: BEN LOMOND
 * # LATITUDE: 37.130940
 * # LONGITUDE: -122.172610
 * # ELEVATION [ft]: 2598
 * # STATE: CA
 * Station_ID,Date_Time,snow_interval_set_1,air_temp_set_1,... <header-line 1: var names>
 * ,,Inches,Fahrenheit,%,Miles/hour,Degrees,Miles/hour,volts,W/m**2,Inches,... <header-line 2: dimensions>
 * BNDC1,08/19/2020 23:50 UTC,,90.0,11.0,5.99,314.0,16.0,13.2,362.0,37.37,,16.0,97.0,3.7,320.0,28.8,,NW,85.48
 * ...
 */
class RawsParser extends ByteCsvPullParser {
  val idLineRE = "# STATION: (.+)".r
  val nameLineRE = "# STATION NAME: (.+)".r
  val latLineRE = "# LATITUDE: (.+)".r
  val lonLineRE = "# LONGITUDE: (.+)".r
  val elevLineRE = """# ELEVATION \[ft\]: (.+)""".r
  val stateLineRE = "# STATE: (.+)".r
  val dateSpecRE = """(\d{2})/(\d{2})/(\d{4}) *(\d{2}):(\d{2}) UTC""".r

  def getWxStationFrom(data: Array[Byte]): Option[WxStation] = {
    if (initialize(data)) {
      try {
        val id: String = nextLine().flatMap(idLineRE.findFirstMatchIn(_)).map(_.group(1)).get
        val name: String = nextLine().flatMap(nameLineRE.findFirstMatchIn(_)).map(_.group(1)).get
        val lat: Double = nextLine().flatMap(latLineRE.findFirstMatchIn(_)).map(_.group(1).toDouble).get
        val lon: Double = nextLine().flatMap(lonLineRE.findFirstMatchIn(_)).map(_.group(1).toDouble).get
        val elev: Double = nextLine().flatMap(elevLineRE.findFirstMatchIn(_)).map(_.group(1).toDouble).get
        val state: String = nextLine().flatMap(stateLineRE.findFirstMatchIn(_)).map(_.group(1)).get

        skipToNextRecord() // ignore field header
        skipToNextRecord() // ignore uom header
        skip(1) // ignore station id field
        readNextValue().toString match {
          case dateSpecRE(mon, day, yr, hr, min) => Some(earth.WxStation(id, name, GeoPosition.fromDegreesAndFeet(lat, lon, elev), min.toInt))
          case s => None
        }
        // .. we could loop over all entries of report to get the max minute but this seems to be invariant for each station
      } catch {
        case x: Throwable =>
          x.printStackTrace()
          None
      }
    } else None
  }

  def getLastBasicRecord(data: Array[Byte]): Option[BasicWxStationRecord] = {
    val nLines = countLines(data)

    if (initialize(data)) {
      var temp = Temperature.UndefinedTemperature
      var spd = Speed.UndefinedSpeed
      var dir = Angle.UndefinedAngle

      repeat(6) {skipToNextRecord()} // skip over comments with meta info
      val fields = readAllValues()
      val units = readAllValues()

      repeat(nLines - 9) { skipToNextRecord() } // skip to last line

      skip(1) // we don't need the station id
      val date = readNextRecordDate() // also fixed

      var i = 2
      while (parseNextValue() && (temp.isUndefined || spd.isUndefined || dir.isUndefined)) {
        fields(i) match {
          case "air_temp_set_1" => units(i) match {
            case "Fahrenheit" => temp = Fahrenheit(value.toDouble)
            case "Celsius" => temp = Celsius(value.toDouble)
            case "Kelvin" => temp = Kelvin(value.toDouble)
          }
          case "wind_speed_set_1" => units(i) match {
            case "Miles/hour" => spd = UsMilesPerHour(value.toDouble)
            case "Meters/second" => spd = MetersPerSecond(value.toDouble)
          }
          case "wind_direction_set_1" => units(i) match {
            case "Degrees" => dir = Degrees(value.toDouble)
          }
          case _ => // we don't care
        }
        i += 1
      }
      if (temp.isDefined && spd.isDefined && dir.isDefined) return Some(BasicWxStationRecord(date, temp, spd, dir))
    }
    None
  }

  def readNextRecordDate(): DateTime = {
    readNextValue() match {
      case dateSpecRE(mon, day, yr, h, m) => DateTime(yr.toInt, mon.toInt, day.toInt, h.toInt, m.toInt, 0, 0, DateTime.utcId)
      case _ => throw new RuntimeException(s"not a record date: $value")
    }
  }

  def countLines(data: Array[Byte]): Int = {
    val len = data.length
    var lines = 0
    var i = 0

    while (i < len) {
      if (data(i) == '\n') lines += 1
      i += 1
    }
    if (!isRecordSeparator(data(len - 1))) lines += 1 // in case last line did not get properly terminated

    lines
  }
}
