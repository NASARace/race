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

package gov.nasa.race.earth.actor

import com.typesafe.config.Config
import gov.nasa.race.core.PeriodicRaceActor
import gov.nasa.race.http.{HttpFileRetriever, RequestFile}
import gov.nasa.race.uom.DateTime

import java.io.File
import scala.concurrent.duration.{DurationInt, FiniteDuration}


class GOESTrueColorFileRetriever (val config: Config) extends HttpFileRetriever with PeriodicRaceActor {
  val url = config.getString("url")
  val fullDir = new File(System.getProperty("user.dir"), config.getString("file-path")) // where it is saved
  val infoUrl = config.getString("info-url")
  override def defaultTickInterval: FiniteDuration = 5.minutes

  override def onRaceTick(): Unit = {
    info("received RaceTick at")
    RequestGOESFile // self ! RequestFile(url, file, true)
    // http method hget check content length of header
  }

  def RequestGOESFile = {
    val now:DateTime = DateTime.now
    val nowDOY: DateTime = DateTime(now.getYear, now.getDayOfYear, now.getHour, now.getMinute, now.getSecond, now.getMillisecond)
    val nowString: String = nowDOY.format_yMdHms // need to save in format of replay files
    val saveTo: File = new File(fullDir.getPath,"GOESR_true_color_c"+nowString+".tif") // need to send full path
    self ! RequestFile(url, saveTo, true)
  }

  def onFirstCall(): Unit = {
    // http get request to the info url
    // parse response of info url
    // get raw date time
    // convert to DateTime
    // get temp tick interval as interval - (current time - tif time)
    // set temp tick interval
    // request first file
  }

  def onSecondCall(): Unit = {
    // reset temp tick interval back to original tick interval
  }
}

