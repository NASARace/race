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
  * tool to change time stamps in GpsPos archives, which are CSV files that contain generated and logged time
  * for each record, which are of format:
  *
  *     1648424332687,1234,1648424332000,37.231195,-121.910724,59,19.7,0,0,492,42,1,0
  *
  * the first timestamp is the logged (replay) time
  */
object GpsPosArchiveRebase extends CsvArchiveRebase {

  def writeDateTime(d: DateTime): Unit = {
    if (nValues > 1) os.write(','.toInt)
    os.write( d.toEpochMillis.toString.getBytes)
  }

  override def rebaseLine(): Unit = {
    val dateLogged = DateTime.ofEpochMillis(readNextValue().toLong)
    if (date0.isUndefined) setTimeDiff(dateLogged)
    writeDateTime(dateLogged + dt)

    passThroughValue() // id

    val dateGenerated = DateTime.ofEpochMillis(readNextValue().toLong)
    writeDateTime(dateGenerated + dt)

    passThroughRemainingValues() // no more time stamps
  }
}
