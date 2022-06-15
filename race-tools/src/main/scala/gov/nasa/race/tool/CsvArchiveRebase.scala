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

import gov.nasa.race.common.{LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.ifSome
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.FileUtils

import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStream}
import java.time.ZoneId

object CsvArchiveRebase {
  class Opts extends CliArgs("usage:") {
    var inFile: Option[File] = None // the text archive file to extract from
    var outFile: Option[File] = None // optional file to store matches in
    var startDate: Option[DateTime] = None // optional start date to use in output
    var zoneId: Option[ZoneId] = None

    opt1("-o", "--out")("<pathName>", s"optional pathname of file to store matching messages (default = $outFile)") { pn =>
      outFile = Some(new File(pn))
    }

    opt1("-s", "--start-date")("<date>", "start time to use in output (e.g. \"2017-08-08T00:44:12Z\")") { s =>
      startDate = Some(parseDateTime(s))
    }

    opt1("-z", "--time-zone")("<timezone>", "time zone (e.g. PT)") { s =>
      zoneId = Some(ZoneId.of(s, ZoneId.SHORT_IDS))
    }

    requiredArg1("<pathName>", "csv archive to rebase") { a =>
      inFile = parseExistingFileOption(a)
    }
  }
}

/**
  * base for all csv archive date rebase tools
  */
trait CsvArchiveRebase extends Utf8CsvPullParser {

  val opts = new CsvArchiveRebase.Opts
  val ClearLine   = "\u001b[1K\u001b[0G"

  var newBaseDate = DateTime.now
  var date0 = DateTime.UndefinedDateTime
  var dt = Time.UndefinedTime
  var nLines = 0
  var os: OutputStream = System.out
  var zoneId: ZoneId = ZoneId.systemDefault()

  def passThroughValue(): Boolean = {
    if (parseNextValue()) {
      if (nValues > 1) os.write(','.toInt)
      os.write(value.data, value.off, value.len)
      true
    } else false
  }

  def passThroughNextValues(n: Int) = {
    var i = 0
    while (i < n && passThroughValue()) {
      i += 1
    }
  }

  def passThroughRemainingValues() = {
    while (passThroughValue()){}
  }

  def setTimeDiff (refDate: DateTime): Unit = {
    date0 = refDate
    dt = newBaseDate.timeSince(date0)
    println(s"old base: $refDate -> delta T = $dt")
    print("reading records ...")
  }

  def rebaseLine(): Unit  // provided by concrete type

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      ifSome(opts.startDate) { newBaseDate = _ }
      ifSome(opts.zoneId) { zoneId = _ }
      ifSome(opts.outFile) { file=> os = new BufferedOutputStream(new FileOutputStream(file), 65536)}

      for (file <- opts.inFile; is <- FileUtils.inputStreamFor(file, 32768)) {
        val lb = new LineBuffer(is)

        println(s"re-basing csv archive $file to new base date: $newBaseDate")

        while (lb.nextLine()) {
          if (initialize(lb)){
            rebaseLine()
            os.write('\n'.toInt)
            nLines += 1
            if ((os ne System.out) && (nLines % 100000 == 0)) {
              print(s"$ClearLine$nLines records written...")
            }
          }
        }

        if (os ne System.out) {
          println(s"$ClearLine$nLines lines written to ${opts.outFile.get}.")
          os.close()
        }
      }
    }
  }
}
