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
package gov.nasa.race.tool

import gov.nasa.race.uom.DateTime

/**
  * tool to change time stamps in SBS archives, which are CSV files that contain generated and logged time
  * for each record
  *
  *    MSG,3,1,1,A825B5,1,2020/08/22,13:13:00.000,2020/08/22,13:13:00.048,,3200,,,37.73698,-121.92311,,,0,,0,0
  *
  * the first date/time pair is the generated (data) time, the second one the logged (replay) time
  * (generated < logged)
  *
  * we rebase based on logged (replay) time, keeping diff between generated and logged constant
  */
object SbsArchiveRebase extends CsvArchiveRebase {

  def readDate (): DateTime = {
    val dateRange = readNextValue().getIntRange
    val timeRange = readNextValue().getIntRange
    DateTime.parseYMDTBytes(data,dateRange.offset,dateRange.length+timeRange.length+1, zoneId)
  }

  def writeDateTime (d: DateTime): Unit = {
    val year = d.getYear(zoneId)
    val month = d.getMonthValue(zoneId)
    val day = d.getDayOfMonth(zoneId)
    val hour = d.getHour(zoneId)
    val min = d.getMinute
    val sec = d.getSecond
    val msec = d.getMillisecond

    val s = f",$year%4d/$month%02d/$day%02d,$hour%02d:$min%02d:$sec%02d.$msec%03d"
    os.write(s.getBytes)
  }

  override def rebaseLine (): Unit = {
    passThroughNextValues(6)

    val dateGenerated = readDate()
    val dateLogged = readDate()

    if (date0.isUndefined) setTimeDiff(dateLogged)

    writeDateTime( dateGenerated + dt)
    writeDateTime( dateLogged + dt)

    passThroughRemainingValues()
  }
}
