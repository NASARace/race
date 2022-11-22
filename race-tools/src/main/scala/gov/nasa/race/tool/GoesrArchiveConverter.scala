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

import ch.qos.logback.classic.{Level, Logger}
import gov.nasa.race.earth.GoesR.{GoesEastId, GoesWestId}
import gov.nasa.race.earth.GoesrDirReader.readDate
import gov.nasa.race.earth.{AbiHotspotReader, GoesRData, GoesrDirReader, GoesrHotspot, GoesrHotspots, GoesRProduct}
import gov.nasa.race.ifSome
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import org.slf4j.LoggerFactory

import java.io.{File, FileOutputStream, OutputStream, PrintStream}
import java.util.zip.GZIPOutputStream

/**
 * tool to convert all GOES-R *.nc files found in the specified directory to CSV
 *
 * file names have to follow the GOES-R distribution convention, e.g.
 *   OR_ABI-L2-FDCC-M6_G17_s20222501106177_e20222501108550_c20222501109166.nc
 *
 * (see links on home.chpc.utah.edu/~u0553130/Brian_Blaylock/cgi-bin/goes16_download.cgi)
 */
object GoesrArchiveConverter {

  // netcdf for some reason defaults to DEBUG level logging - turn off
  val root: Logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
  root.setLevel(Level.ERROR)

  class ConvertOpts extends CliArgs("usage:") {
    var dir: Option[File] = None // the text archive file to extract from
    var singleArchive = false
    var compress = false

    opt0("-s","--single")("collect into one archive") {singleArchive = true}
    opt0("-c","--compress")("compress (gzip) archive(s)") {compress = true}

    requiredArg1("<pathName>", "directory to scan for GOES-R files") { a =>
      dir = parseExistingDirOption(a)
    }
  }

  val opts = new ConvertOpts

  val product = GoesRProduct("ABI-L2-FDCC", "<none>", Some(new AbiHotspotReader))
  val satIds = Map[String,Int]("G16" -> GoesEastId, "G17" -> GoesWestId)

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (dir <- opts.dir) {
        println(s"reading directory: $dir")

        var first: DateTime = DateTime.NeverDateTime
        var last: DateTime = DateTime.Date0

        var gosFile: Option[File] = None
        var gps: Option[PrintStream] = None
        if (opts.singleArchive) {
          gosFile = Some( getOutFile( s"$dir/OR_ABI-L2-FDCC.csv", opts.compress))
          gps = Some(new PrintStream(getOutputStream(gosFile.get)))
          GoesrHotspot.printCsvHeaderTo(gps.get)
        }
        var nRecords = 0
        var dLast = DateTime.Date0

        GoesrDirReader.timeSortedNcFilesInDir(dir.getPath, "OR_ABI-L2-FDCC-*.nc").foreach { file =>
          file.getName match {
            case GoesrDirReader.FnameRE(prod, modChan, sat, start, end, create) =>
              println(s"converting $file")
              val date = readDate(create)
              if (date < first) first = date
              if (date > last) last = date

              ifSome(satIds.get(sat)) { satId =>
                val data = GoesRData(satId, file, product, date)
                for (reader <- data.product.reader; result <- reader.read(data)) {
                  result match {
                    case list: GoesrHotspots =>
                      if (list.date > dLast) {
                        val ps = gps match {
                          case Some(stream) => stream
                          case None =>
                            val fn = file.getName.replace(".nc", ".csv")
                            val osFile = getOutFile(s"$dir/$fn", opts.compress)
                            val ps = new PrintStream(getOutputStream(osFile))
                            GoesrHotspot.printCsvHeaderTo(ps)
                            ps
                        }

                        list.data.foreach(hs => hs.printCsvTo(ps))
                        nRecords += list.data.size

                        if (!opts.singleArchive) ps.close()
                        dLast = list.date
                      } else println(s"skipping out-of-order date ${list.date} for file $file")

                    case other => println(s"reading GOES-R ABI-L2-FDCC file failed")
                  }
                }
              }
            case _ => // ignore non-matching file name
          }
        }

        ifSome(gps) { stream=>
          stream.close()
          val file = getOutFile( s"$dir/OR_ABI-L2-FDCC_s${first.format_yMdHms}_e${last.format_yMdHms}.csv", opts.compress)
          gosFile.get.renameTo(file)
        }

        println(s"$nRecords hotspot records archived")
      }
    }
  }

  def getOutFile (pathName: String, compress: Boolean): File = {
    val pn = pathName.replace(".nc", ".csv")
    if (compress) new File(pn + ".gz") else new File(pn)
  }

  def getOutputStream (file: File): OutputStream = {
    val fos = new FileOutputStream(file)
    if (file.getName.endsWith(".gz")) new GZIPOutputStream(fos) else fos
  }
}
