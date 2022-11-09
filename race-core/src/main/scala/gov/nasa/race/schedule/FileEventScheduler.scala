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

import gov.nasa.race._
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.UTF8XmlPullParser2
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.util.FileUtils

import java.io._

object FileEventScheduler {
  val SCHEDULE = asc("schedule")
  val EVENT = asc("event")
  val WHEN = asc("when")
  val FILE = asc("file")
}

/**
 * a EventScheduler that processes files
 *
 * schedule files are in XML:
 *   <schedule>
 *     <event when="date-or-duration" file="pathname"/>
 *   </schedule>
 */
class FileEventScheduler (val action: (File)=>Unit) extends UTF8XmlPullParser2 with EventScheduler {
  import FileEventScheduler._

  def loadSchedule (scheduleSpec: Array[Byte]): Unit = {
    initialize(scheduleSpec)

    while (parseNextTag) {
      if (isStartTag) {
        tag match {
          case SCHEDULE => // new schedule

          case EVENT =>
            var pathName: String = null
            var timeSpec: String = null

            while (parseNextAttr) {
              attrName match {
                case WHEN => timeSpec = attrValue.toString
                case FILE => pathName = attrValue.toString
              }
            }

            if (pathName != null && timeSpec != null) {
              ifSome(FileUtils.existingNonEmptyFile(pathName)) { f =>
                timeSpec match {
                  case hhmmssRE(hh,mm,ss) => scheduleAfter(Time.HMS(hh.toInt,mm.toInt,ss.toInt))(action(f))
                  case iso8601PeriodRE(s) => scheduleAfter(Time.parse(s))(action(f))
                  case dateTimeRE(s) => scheduleAt(DateTime.parseYMDT(s))(action(f))
                }
              }
            }
        }
      }
    }
  }

  def loadSchedule (scheduleSpec: String): Unit = loadSchedule(scheduleSpec.getBytes)
  def loadSchedule (scheduleSpec: File): Unit = ifSome (FileUtils.fileContentsAsBytes(scheduleSpec)) { loadSchedule }

}
