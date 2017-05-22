/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.actor

import java.io._

import gov.nasa.race.config.ConfigUtils._
import com.typesafe.config.Config
import gov.nasa.race.common.FileStats
import gov.nasa.race.util.BufferedFileWriter

/**
  * a StatsReporter that writes to a file
  */
class FileStatsReporter (val config: Config) extends PrintStatsReporterActor {

  def defaultPathName = s"tmp/$name" // override in concrete class
  val reportFile = new File(config.getStringOrElse("pathname", defaultPathName))

  val writer = new BufferedFileWriter(reportFile, config.getIntOrElse("buffer-size",16384), false)
  val pw = new PrintWriter(writer)

  override def report = {
    writer.reset

    topics.valuesIterator foreach { s =>
      if (!handledByFormatter(s)) {
        s match {
          case fs: FileStats => fs.writeToFile(pw)
          case _ => // ignore
        }
      }
    }

    pw.flush
    writer.writeFile
  }
}
