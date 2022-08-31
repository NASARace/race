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

import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth.{GpsPos, GpxParser}
import gov.nasa.race.whileSome

import java.io.{File, FileOutputStream, PrintStream}


class GpxConvertOpts extends CliArgs("usage:") {
  var inFile: Option[File] = None // the text archive file to extract from
  var outFile: Option[File] = None // optional file to store matches in
  var startDate: Option[DateTime] = None // optional start date to use in output
  var timeScale: Option[Double] = None // optional time scale to adjust entry dates in output
  var id: Option[String] = None // optional override for track id
  var role: Option[Int] = None
  var org: Option[Int] = None
  var status: Option[Int] = None

  opt1("-o", "--out")("<pathName>", s"optional pathname of file to store matching messages (default = $outFile)") { pn =>
    outFile = Some(new File(pn))
  }
  opt1("--start-date")("<date>", "optional start time to use in output (e.g. \"2017-08-08T00:44:12Z\")") { s=>
    startDate = Some(parseDateTime(s))
  }
  opt1("--time-scale")("<factor>", "optional time scale to adjust entry dates in output") { s=>
    timeScale = Some(parseDouble(s))
  }
  opt1("--id")("<name>", "optional override for track id") { s=>
    id = Some(s)
  }
  opt1("--role")("<code>", "optional override for track role") { s=>
    role = Some(s.toInt)
  }
  opt1("--org")("<code>", "optional override for track org") { s=>
    org = Some(s.toInt)
  }
  opt1("--status")("<code>", "optional override for track status") { s=>
    status = Some(s.toInt)
  }
  requiredArg1("<pathName>", "text archive to repair") { a =>
    inFile = parseExistingFileOption(a)
  }
}

/**
  * tool to create GpsPos CSV archives from *.gpx files
  */
object GpxArchiveConverter {
  var opts = new GpxConvertOpts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (file <- opts.inFile) {
        val ps = opts.outFile match {
          case Some(oFile) => new PrintStream( new FileOutputStream(oFile))
          case None => System.out
        }
        val parser = new GpxParser
        var n = 0
        var date0 = DateTime.UndefinedDateTime
        var dt = Time.UndefinedTime
        val ts: Double = 1.0 / opts.timeScale.getOrElse(1.0)

        def adaptedDate(date: DateTime): DateTime = {
          if (opts.startDate.isDefined) {
            if (opts.timeScale.isDefined) opts.startDate.get + (date.timeSince(date0) * ts)
            else date + dt
          } else if (opts.timeScale.isDefined) {
            date0 + (date.timeSince(date0) * ts)
          } else {
            date
          }
        }

        FileUtils.fileContentsAsBytes(file) match {
          case Some(input) =>
            println(s"converting GPX archive: $file")
            if (parser.parseHeader(input)) {
              if (parser.parseToNextTrack()) {
                whileSome(parser.parseNextTrackPoint()) { rec=>
                  var gps = rec
                  if (date0.isUndefined) {
                    date0 = gps.date
                    dt = if (opts.startDate.isDefined) opts.startDate.get.timeSince(gps.date) else Time.Time0
                  }

                  val d = adaptedDate(gps.date)
                  if (d != gps.date) gps = gps.copy(date = d)

                  if (opts.id.isDefined) gps = gps.copy(id = opts.id.get)
                  if (opts.role.isDefined) gps = gps.copy(role = opts.role.get)
                  if (opts.org.isDefined) gps = gps.copy(org = opts.org.get)
                  if (opts.status.isDefined) gps = gps.copy(status = opts.status.get)

                  ps.print(gps.date.toEpochMillis) // simulated log time
                  ps.print(",")
                  gps.serializeCsvTo(ps)
                  n += 1
                }
              } else println("Error: failed to parse trk")
            } else println("Error: failed to parse gpx header")

          case None => println("Error: no input file")
        }

        if (n > 0) {
          opts.outFile match {
            case Some(oFile) =>
              ps.close()
              println(s"$n GPS records written to $oFile")
            case None => // done
          }
        }
      }
    }
  }
}
