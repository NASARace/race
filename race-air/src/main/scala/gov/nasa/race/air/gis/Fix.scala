/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.air.gis

import java.io.File
import java.nio.ByteBuffer

import gov.nasa.race.common.{ConstAsciiSlice, JsonPullParser, StringJsonPullParser}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.gis.{GisItem, GisItemDB, GisItemDBFactory}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.util.{ConsoleIO, FileUtils, NetUtils}

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer
import scala.util.Either

/**
  * Fix as defined on https://www.faa.gov/air_traffic/flight_info/aeronav/aero_data/Loc_ID_Search/Fixes_Waypoints/
  */
case class Fix (name: String,
                pos: GeoPosition,
                navaid: Option[String]
               ) extends GisItem {
  override def toString: String = {
    navaid match {
      case Some(s) => s"""Fix("$name",${pos.toGenericString2D},"$s""""
      case None => s"""Fix("$name",${pos.toGenericString2D})"""
    }
  }
}


/**
  * parse fixes from http query:
  *
  *    https://nfdc.faa.gov/nfdcApps/controllers/PublicDataController/getLidData?
  *                  dataType=LIDFIXESWAYPOINTS&length=..&sortcolumn=fix_identifier&sortdir=asc&start=..
  *
  * which yields a Json result such as:
  *
  *   {
  *     "totalrows": 4481,
  *     "totaldisplayrows": 4481,
  *     "data": [
  *         {
  *            "fix_identifier":"AAALL",
  *            "state":"MASSACHUSETTS",
  *            "description":"42-07-12.6800N 071-08-30.3400W"
  *         },
  *         {
  *            "fix_identifier": "ADUDE",
  *            "state": "CALIFORNIA",
  *            "description": "JLI*C*070.00/21.00 33-10-13.1800N 116-10-13.9800W"
  *         },
  *         ...
  *      ]
  *   }
  */
class FixParser extends StringJsonPullParser {
  import JsonPullParser._

  val DescrRE = """(?:(.+)\s+)?(\d+)-(\d+)-(\d+.\d+)(N|S)\s+(\d+)-(\d+)-(\d+.\d+)(E|W)""".r

  val _totalrows_ = ConstAsciiSlice("totalrows")
  val _totaldisplayrows_ = ConstAsciiSlice("totaldisplayrows")
  val _data_ = ConstAsciiSlice("data")
  val _fix_identifier_ = ConstAsciiSlice("fix_identifier")
  val _description_ = ConstAsciiSlice("description")
  val _state_ = ConstAsciiSlice("state")

  def parse (input: String): Seq[Fix] = {
    var list: ArrayBuffer[Fix] = ArrayBuffer.empty[Fix]

    def getDeg (d: Int, m: Int, s: Double, dir: String): Double = {
      var x = d.toDouble + (m/60.0) + (s/3600.0)
      if (dir == "W" || dir == "S") -x else x
    }

    def parseFix: Unit = {
      ensureNextIsObjectStart()
      val fixId = readQuotedMember(_fix_identifier_).toString
      val state = readQuotedMember(_state_).intern

      val descr = readQuotedMember(_description_).toString  // not very efficient but this is a offline tool
      descr match {
        case DescrRE(nav, slatDeg, slatMin, slatSec, slatDir, slonDeg, slonMin, slonSec, slonDir) =>
          val lat = getDeg(slatDeg.toInt, slatMin.toInt, slatSec.toDouble, slatDir)
          val lon = getDeg(slonDeg.toInt, slonMin.toInt, slonSec.toDouble, slonDir)
          val pos = GeoPosition.fromDegrees(lat, lon)
          val navaid = Option(nav)
          list += Fix(fixId,pos,navaid)

        case _ => println(s"invalid fix description for id=$fixId: '$descr'")
      }

      ensureNextIsObjectEnd()
    }

    if (initialize(input)){
      ensureNextIsObjectStart()
      val totalRows = readUnQuotedMember(_totalrows_).toInt
      val totalDisplayRows = readUnQuotedMember(_totaldisplayrows_).toInt
      foreachInNextArrayMember(_data_) {
        parseFix
      }
      ensureNextIsObjectEnd()
    }
    list
  }
}

object FixDB extends GisItemDBFactory[Fix](60) {

  val parser = new FixParser

  override protected def writeItemPayloadFields(it: Fix, buf: ByteBuffer): Unit = {
    it.navaid match {
      case Some(s) => buf.putInt(strMap(s))
      case None => buf.putInt(-1)
    }
  }

  override def loadDB(file: File): Option[GisItemDB[Fix]] = {
    mapFile(file).map(new FixDB(_))
  }

  /**
    * create GisItemDB by running online queries on FAA website
    */
  override def createDB (outFile: File, extraArgs: Seq[String], date: DateTime): Boolean = {
    if (FileUtils.ensureWritable(outFile).isDefined){
      reset

      val url = "https://nfdc.faa.gov/nfdcApps/controllers/PublicDataController/getLidData"
      val extraParams = extraArgs.mkString("&","&","")
      var r = 0
      var n = 0

      println(s"retrieving fixes from $url..")
      do {
        ConsoleIO.line(s"read $n fixes")
        val params = s"dataType=LIDFIXESWAYPOINTS&length=1000&sortcolumn=fix_identifier&sortdir=asc&start=$n$extraParams"
        NetUtils.blockingHttpsPost(url,params) match {
          case Right(json) =>
            val fixes = parser.parse(json)
            fixes.foreach(addItem)
            r = fixes.size
          case Left(err) => println(s"aborting with error: $err")
        }
        n += r
      } while (r > 0)
      ConsoleIO.line(s"read $n fixes")

      if (items.nonEmpty) {
        write(outFile, date)
        true

      } else {
        println("no items read")
        false
      }

    } else {
      println(s"invalid output file: $outFile")
      false
    }
  }
}


class FixDB (data: ByteBuffer) extends GisItemDB[Fix](data) {

  def this (file: File) = this(GisItemDB.mapFile(file))

  override protected def readItem(iOff: Int): Fix = {
    val buf = data
    buf.position(iOff + 28) // skip over hash and xyz coords

    val lat = buf.getDouble
    val lon = buf.getDouble
    val elev = buf.getDouble
    val pos = GeoPosition(Degrees(lat),Degrees(lon),Feet(elev))

    val name = strings(buf.getInt)

    val sIdx = buf.getInt
    val navaid = if (sIdx == -1) None else Some(strings(sIdx))

    Fix(name,pos,navaid)
  }
}