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
import gov.nasa.race.common.{ConstAsciiSlice, JsonParseException, JsonPullParser, StringJsonPullParser}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.gis.{GisItem, GisItemDB, GisItemDBFactory}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.util.{ConsoleIO, FileUtils, NetUtils}

import scala.collection.{Seq, mutable}
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
      case Some(s) => s"""Fix("$name",${pos.toGenericString2D},"$s")"""
      case None => s"""Fix("$name",${pos.toGenericString2D})"""
    }
  }

  override def addStrings (db: GisItemDBFactory[_]): Unit = {
    db.addString(name)
    if (navaid.isDefined) db.addString(navaid.get)
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

  val DescrRE = """(?:(.+)\s+)?(\d+)-(\d+)-(\d+.\d+)(N|S)\s+(\d+)-(\d+)-(\d+.\d+)(E|W)""".r

  val TOTAL_ROWS = ConstAsciiSlice("totalrows")
  val TOTAL_DISPLAY_ROWS = ConstAsciiSlice("totaldisplayrows")
  val DATA = ConstAsciiSlice("data")
  val FIX_ID = ConstAsciiSlice("fix_identifier")
  val DESCRIPTION = ConstAsciiSlice("description")
  val STATE = ConstAsciiSlice("state")


  def getDeg (d: Int, m: Int, s: Double, dir: String): Double = {
    val x = d.toDouble + (m/60.0) + (s/3600.0)
    if (dir == "W" || dir == "S") -x else x
  }

  def readFix(): Option[Fix] = {
    var id: String = null
    var state: String = null
    var pos: GeoPosition = null
    var navaid: Option[String] = None

    foreachMemberInCurrentObject {
      case FIX_ID => id = quotedValue.toString
      case STATE => state = quotedValue.intern  // there aren't that many
      case DESCRIPTION =>
        quotedValue match {
          case DescrRE(nav, slatDeg, slatMin, slatSec, slatDir, slonDeg, slonMin, slonSec, slonDir) =>
            val lat = getDeg(slatDeg.toInt, slatMin.toInt, slatSec.toDouble, slatDir)
            val lon = getDeg(slonDeg.toInt, slonMin.toInt, slonSec.toDouble, slonDir)

            pos = GeoPosition.fromDegrees(lat, lon)
            navaid = Option(nav)

          case _ => // ignore, we don't have a position
        }
    }

    if (id != null && pos != null) {
      Some( Fix(id,pos,navaid) )
    } else {
      None
    }
  }

  def readFixes(): Seq[Fix] = {
    val buf = mutable.Buffer.empty[Fix]
    foreachElementInCurrentArray {
      readCurrentObject( readFix() ) match {
        case Some(fix) => buf += fix
        case None =>
      }
    }
    buf.toSeq
  }

  def parse (input: String): Seq[Fix] = {
    var data = Seq.empty[Fix]

    if (initialize(input)) {
      try {
        readNextObject {
          var totalRows: Int = 0
          var totalDisplayRows = 0

          foreachMemberInCurrentObject {
            case TOTAL_ROWS =>  totalRows = unQuotedValue.toInt
            case TOTAL_DISPLAY_ROWS => totalDisplayRows = unQuotedValue.toInt
            case DATA => data = readCurrentArray( readFixes() )
          }
        }
      } catch {
        case x: JsonParseException => // should warn here but this also terminates on "{ "aadata": [] }"
      }
    }

    data
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
        val params = s"dataType=LIDFIXESWAYPOINTS&start=$n&length=1000&sortcolumn=fix_identifier&sortdir=asc$extraParams"
        NetUtils.blockingHttpsPost(url,params) match {
          case Right(json) =>
            val fixes: Seq[Fix] = parser.parse(json)
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