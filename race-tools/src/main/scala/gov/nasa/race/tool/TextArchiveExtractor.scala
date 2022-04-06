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
package gov.nasa.race.tool

import java.io.File

import gov.nasa.race.archive.TextArchiveReader
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.FileUtils
import gov.nasa.race.uom.DateTime

import scala.util.matching.Regex

/**
  * tool to extract matching messages from a potentially compressed text archive
  */
object TextArchiveExtractor {

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var inFile: Option[File] = None // the text archive file to extract from
    var outFile: Option[File] = None // optional file to store matches in
    var patterns: Seq[Regex] = Seq.empty // regexes to match in message text
    var startTime: Long = Long.MinValue // start date for matches
    var endTime: Long = Long.MaxValue // end date for matches
    var matchAll: Boolean = false // do we try to find all matches (default is to stop after first match)

    opt1("-o", "--out")("<pathName>", s"optional pathname of file to store matching messages (default = $outFile)") { pn =>
      outFile = Some(new File(pn))
    }
    opt1("--start-time")("<timespec>", "start time for messages to extract") { s =>
      startTime = parseTimeMillis(s)
    }
    opt1("--end-time")("<timespec>", "end time for messages to extract") { s =>
      endTime = parseTimeMillis(s)
    }
    opt1("-nid", "--pattern")("<regex>", "regular expression(s) to match") { s=>
      patterns = new Regex(s) +: patterns
    }
    opt0("-a")("find all matches (default is to stop after first match") {
      matchAll = true
    }

    requiredArg1("<pathName>", "text archive to extract from") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (file <- opts.inFile;
           is <- FileUtils.inputStreamFor(file,8192)){
        processArchiveEntries(new TextArchiveReader(is, file.getPath))
        is.close
      }
    }
  }

  def processArchiveEntries(ar: TextArchiveReader): Unit = {
    while (ar.hasMoreArchivedData) {
      ar.readNextEntry() match {
        case Some(e) =>
          val dateMillis = e.date.toEpochMillis
          val msg = e.msg.toString

          if (dateMillis >= opts.startTime && dateMillis <= opts.endTime) {
            if (opts.patterns.isEmpty || opts.patterns.exists( _.findFirstIn(msg).isDefined)){
              processMatch(e.date,msg)
              if (!opts.matchAll) return
            }
          }

        case None =>
      }
    }
  }

  def processMatch (date: DateTime, msg: String) = {
    println(msg)
  }
}