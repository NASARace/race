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

package gov.nasa.race.common

import java.io._
import org.joda.time.{DateTime,Duration}
import DateTimeUtils._

import scala.xml.{NodeSeq, Text, XML}

/**
 * a EventScheduler that processes files
 *
 * schedule files are in XML:
 *   <schedule>
 *     <event when="date-or-duration" file="pathname"/>
 *   </schedule>
 */
class FileEventScheduler (val action: (File)=>Unit) extends EventScheduler {

  def loadSchedule (scheduleSpec: String): Unit = loadSchedule(new StringReader(scheduleSpec))
  def loadSchedule (scheduleSpec: File): Unit = loadSchedule(new InputStreamReader(new FileInputStream(scheduleSpec)))
  def loadSchedule (scheduleSpec: Reader): Unit = {
    val doc = XML.load(scheduleSpec)
    for (e <- doc \\ "event") {
      val fileAttr = new File((e \ "@file").toString)
      val whenAttr = (e \ "@when").toString match {
        case hhmmssRE(hh,mm,ss) => schedule(duration(hh.toInt,mm.toInt,ss.toInt))(action(fileAttr))
        case iso8601PeriodRE(s) => schedule(isoPeriodFormatter.parsePeriod(s).toStandardDuration)(action(fileAttr))
        case dateTimeRE(s) => schedule(DateTime.parse(s))(action(fileAttr))
      }
    }
  }

}

object FileEventScheduler {

  def processFile(file: File): Unit = println(file)

  def main(args: Array[String]): Unit = {
    val input =
      """
        |<schedule>
        |  <event when="00:00:05" file="data/five"/>
        |  <event when="00:00:06" file="data/six"/>
        |  <event when="00:00:03" file="data/three"/>
        |</schedule>
      """.stripMargin

    val scheduler = new FileEventScheduler(processFile)
    scheduler.loadSchedule(input)
    println("start processing events..")
    scheduler.processEventsSync(DateTime.now)
  }
}
