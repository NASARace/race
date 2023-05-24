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

import com.typesafe.config.Config
import gov.nasa.race.actor.FileReplayActor
import gov.nasa.race.common.FileAvailable
import gov.nasa.race.http.{FileRetrieved, RequestFile}
import gov.nasa.race.uom.DateTime

import java.io.File

class GoesTrueColorReplayActor(val config: Config) extends FileReplayActor[TifFileAvailable] {

  val tifPattern = raw".*_c(\d{4})(\d{3})(\d{2})(\d{2})(\d{2})(\d)\.tif".r
  Thread.sleep(4000) // bad practice - needed to allow python server to start
  override protected def getFileAvailable(f: File): Option[TifFileAvailable] = { // pattern matching here
    f.getName match {
      case tifPattern(year, day, hour, min, sec, msec) =>
        val date = DateTime(year.toInt, day.toInt, hour.toInt, min.toInt, sec.toInt, msec.toInt)
        val tempFile: File = new File( dir.getPath, f.getName)
        val fullFile: File = new File(System.getProperty("user.dir"), tempFile.getPath)
        Some(TifFileAvailable(date, fullFile, "unavailable"))
      case _ => None
    }
  }

  override protected def publishFileAvailable(fa: TifFileAvailable): Unit = {
    publish(getTifFileRetrieved(fa))
  }

  private def getTifFileRetrieved(fa: TifFileAvailable): FileRetrieved = {
    FileRetrieved(RequestFile(fa.url, fa.file), fa.date)
  }
}

case class TifFileAvailable (date: DateTime, file: File, url: String) extends FileAvailable
