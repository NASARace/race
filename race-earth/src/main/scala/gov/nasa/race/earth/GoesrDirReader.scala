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

import gov.nasa.race.earth.GoesR.{GoesEastId, GoesWestId}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import java.io.File

object GoesrDirReader {
  val FnameRE = "OR_(ABI-.*-.*)-(.*)_(.*)_s(\\d{14})_e(\\d{14})_c(\\d{14}).nc".r
  val DateRE = "(\\d{4})(\\d{3})(\\d{2})(\\d{2})(\\d{2})(\\d)".r

  def readDate (s: String): DateTime = {
    s match {
      case DateRE(year,dayOfYear,hour,min,sec,tenthSec) =>
        DateTime( year.toInt, dayOfYear.toInt, hour.toInt, min.toInt, sec.toInt, (tenthSec.toInt * 100))
      case _ => DateTime.UndefinedDateTime
    }
  }

  def createSuffix (fname: String): String = {
    val idx = fname.lastIndexOf('_')
    if (idx >= 0) fname.substring(idx+2, idx+16) else ""
  }

  def sortFileCreation (a: File, b: File): Boolean = {
    createSuffix(a.getName) < createSuffix(b.getName)
  }

  def unsortedNcFilesInDir (dirPath: String, globPattern: String = "OR_*.nc"): Seq[File] = {
    FileUtils.getMatchingFilesIn(dirPath, globPattern)
  }

  def timeSortedNcFilesInDir (dirPath: String, globPattern: String = "OR_*.nc"): Seq[File] = {
    FileUtils.getMatchingFilesIn(dirPath, globPattern).sortWith( sortFileCreation)
  }
}
import GoesrDirReader._

/**
 * utility  to read and time sort all GoesR data files in a given directory.
 * Archive file names follow this pattern (from https://home.chpc.utah.edu/~u0553130/Brian_Blaylock/cgi-bin/goes16_download.cgi)
 *
 *    OR_ABI-L1b-RadM1-M3C01_G16_s20172511100550_e20172511101007_c20172511101048.nc
 *
 *    OR - data is operational and in real-time
 *    ABI-L1b-RadM1- - is the product, with the mesoscale 1 domain. C is for CONUS, F is for full disk, and M2 is for Mesoscale 2.
 *    M3C01 - Mode is 3 and Channel is 01
 *    G16 - GOES-16 (G17 for GOES-17)
 *    s20172511100550 - scan start time sYYYYJJJHHMMSSm: year, day of year, hour, minute, second, tenth second
 *    e20172511101007 - scan end time sYYYYJJJHHMMSSm: year, day of year, hour, minute, second, tenth second
 *    c20172511101048 - scan file creation time sYYYYJJJHHMMSSm: year, day of year, hour, minute, second, tenth second
 *
 *    for active fire (L2) this is for instance
 *    OR_ABI-L2-FDCC-M6_G17_s20222501106177_e20222501108550_c20222501109166.nc
 */
trait GoesrDirReader {

  def getGoesRData (dirPath: String, products: Seq[GoesRProduct], satId: Int, startDate: DateTime, endDate: DateTime): Seq[GoesRData] = {
    val satName = satId match {
      case GoesWestId => "G17"
      case GoesEastId => "G16"
      case _ => return Seq.empty[GoesRData] // unknown satellite
    }

    unsortedNcFilesInDir(dirPath).flatMap { f=>
      f.getName match {
        case FnameRE(prod,modChan,sat,start,end,create) =>
          if (satName == sat) {
            products.find( _.name == prod) match {
              case Some(product) =>
                val date = readDate(create)
                if (date.isWithin(startDate, endDate)) Some(GoesRData(satId, f, product, date))
                else None // outside date range
              case _ => None // not a requested product
            }
          } else None // wrong satellite
        case _ => None // not a GOES-R data set
      }
    }.sortWith( (a,b) => a.date < b.date)
  }
}
