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
package gov.nasa.race.space

import com.typesafe.config.Config
import gov.nasa.race.common.{LineBuffer, NearestLookupTable}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import java.io.{File, FileInputStream}
import java.util.zip.GZIPInputStream
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


/**
 * something that reads stored (historic) TLEs and supports finding the closest TLE entry for a given date
 * Archives are using normal 3le format obtained from https://www.space-track.org/#/gp :
 *
 * 0 NOAA 20
 * 1 43013U 17073A   20229.19659373 -.00000028  00000-0  75342-5 0  9994
 * 2 43013  98.7192 166.2240 0001234 104.0231 256.1082 14.19559360142139
 * 0 NOAA 20
 * 1 43013U 17073A   20229.47853004 -.00000028 +00000-0 +73690-5 0  9996
 * 2 43013 098.7192 166.5017 0001233 103.9121 256.2193 14.19559367142174
 * ...
 *
 * Note that files could contain entries for different satellites and don't need to be sorted, but
 * they cannot contain empty lines or additional text
 */
trait TleArchiveOwner {
  val config: Config

  protected val tleArchives = config.getStrings("tle-archive").map( new File(_))
  protected val tleMap = readTLEs(tleArchives)

  protected def numberOfTLESatellites: Int = tleMap.keys.size

  private def readTLEs( archives: Array[File]): Map[Int,NearestLookupTable[Long,TLE]] = {
    val satMap = mutable.Map.empty[Int,ArrayBuffer[TLE]]

    archives.foreach { archive =>
      val fis = new FileInputStream(archive)
      val is = if (FileUtils.getExtension(archive) == "gz") new GZIPInputStream(fis) else fis
      val lb = new LineBuffer(is)

      while (!lb.hasReachedEnd) {
        val l0 = if (lb.nextLine()) lb.toString else ""
        val l1 = if (lb.nextLine()) lb.toString else ""
        val l2 = if (lb.nextLine()) lb.toString else ""

        if (l0.nonEmpty && l1.nonEmpty && l2.nonEmpty) {
          val tle = TLE(l0, l1, l2)

          val list = satMap.getOrElseUpdate(tle.catNum, ArrayBuffer.empty)
          list += tle
        }
      }
      is.close()
    }

    satMap.map( e=> (e._1, NearestLookupTable.from[Long,TLE]( e._2.toArray, (tle:TLE) => tle.date.toEpochMillis))).toMap
  }

  def findClosestTLE (satId: Int, date: DateTime): Option[TLE] = {
    tleMap.get(satId).map( _.findNearest(date.toEpochMillis)._2)
  }

  // BEWARE - this is probably not what you want
  def findClosestTLE(date: DateTime): Option[TLE] = {
    var minTd = Long.MaxValue
    var closestTLE: Option[TLE] = None

    tleMap.foreach { e=>
      val (td, tle) = e._2.findNearest(date.toEpochMillis)
      if (td < minTd) {
        minTd = td
        closestTLE = Some(tle)
      }
    }

    closestTLE
  }

  def foreachClosestTLE(date: DateTime)(f: TLE=>Unit): Unit = {
    tleMap.foreach { e =>
      val (td, tle) = e._2.findNearest(date.toEpochMillis)
      f(tle)
    }
  }

  def findTLE (satId: Int, date: DateTime): Option[TLE] = {
    tleMap.get(satId).map( _.findLessOrEqual(date.toEpochMillis)._2)
  }
}
