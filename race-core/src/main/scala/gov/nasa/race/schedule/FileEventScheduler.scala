/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.schedule

import java.io._

import gov.nasa.race._
import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.util.{FileUtils, XmlAttrProcessor, XmlPullParser}
import org.joda.time.DateTime

/**
 * a EventScheduler that processes files
 *
 * schedule files are in XML:
 *   <schedule>
 *     <event when="date-or-duration" file="pathname"/>
 *   </schedule>
 */
class FileEventScheduler (val action: (File)=>Unit) extends XmlPullParser with XmlAttrProcessor with EventScheduler {

  def loadSchedule (scheduleSpec: Array[Char]): Unit = {
    initialize(scheduleSpec)

    while (parseNextElement()) {
      if (isStartElement) {
        tag match {
          case "schedule" => // new schedule
          case "event" =>
            var pathName: String = null
            var timeSpec: String = null

            processAttributes {
              case "when" => timeSpec = value
              case "file" => pathName = value
            }

            if (pathName != null && timeSpec != null) {
              ifSome(FileUtils.existingNonEmptyFile(pathName)) { f =>
                timeSpec match {
                  case hhmmssRE(hh,mm,ss) => schedule(duration(hh.toInt,mm.toInt,ss.toInt))(action(f))
                  case iso8601PeriodRE(s) => schedule(isoPeriodFormatter.parsePeriod(s).toStandardDuration)(action(f))
                  case dateTimeRE(s) => schedule(DateTime.parse(s))(action(f))
                }
              }
            }
        }
      }
    }
  }
  def loadSchedule (scheduleSpec: String): Unit = loadSchedule(scheduleSpec.toCharArray)
  def loadSchedule (scheduleSpec: File): Unit = ifSome (FileUtils.fileContentsAsChars(scheduleSpec)) { loadSchedule }

}
