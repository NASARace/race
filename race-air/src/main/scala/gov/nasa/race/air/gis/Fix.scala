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

import java.io.{DataOutputStream, File}
import java.nio.ByteBuffer

import scala.util.Either
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.gis.{GisItem, GisItemDB, GisItemDBFactory}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.util.{ConsoleIO, FileUtils, LeDataOutputStream, NetUtils}
import io.circe
import io.circe._
import org.joda.time.DateTime

/**
  * Fix as defined on https://www.faa.gov/air_traffic/flight_info/aeronav/aero_data/Loc_ID_Search/Fixes_Waypoints/
  */
case class Fix (name: String,
                pos: GeoPosition,
                navaid: Option[String]
               ) extends GisItem


/**
  * parse from Json query results with format:
  *
  *   {
  *     "totalrows": 4481,
  *     "totaldisplayrows": 4481,
  *     "data": [
  *         {
  *            "fix_identifier": "ADUDE",
  *            "state": "CALIFORNIA",
  *            "description": "JLI*C*070.00/21.00 33-10-13.1800N 116-10-13.9800W"
  *         },
  *         ...
  *      ]
  *   }
  */
object FixDB extends GisItemDBFactory[Fix] {

  val DescrRE = """(?:(.+)\s+)?(\d+)-(\d+)-(\d+.\d+)(N|S)\s+(\d+)-(\d+)-(\d+.\d+)(E|W)""".r

  override val schema: String = "gov.nasa.race.air.gis.Fix"
  override val itemSize: Int = 56 + 4

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
            val fixes = parseJson(json)
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

  //--- JSon decoding (using io.circe - TODO this is overkill, replace with a JsonPullParser)

  def getDeg (d: Int, m: Int, s: Double, dir: String): Double = {
    var x = d.toDouble + (m/60.0) + (s/3600.0)
    if (dir == "W" || dir == "S") -x else x
  }

  def getFix(hCursor: HCursor, id: String, descr: String): Either[DecodingFailure,Fix] = {
    descr match {
      case DescrRE(nav, slatDeg,slatMin,slatSec,slatDir, slonDeg,slonMin,slonSec,slonDir) =>
        val lat = getDeg(slatDeg.toInt,slatMin.toInt,slatSec.toDouble,slatDir)
        val lon = getDeg(slonDeg.toInt,slonMin.toInt,slonSec.toDouble,slonDir)
        val pos = GeoPosition.fromDegrees(lat,lon)
        val navaid = Option(nav)
        Right(Fix(id,pos,navaid))
      case _ => Left(circe.DecodingFailure(s"failed to parse description of '$id': $descr",hCursor.history))
    }
  }

  private var lastId: String = null // for error reporting

  implicit val fixDecoder: Decoder[Fix] = (hCursor: HCursor) => {
    for {
      id <- hCursor.get[String]("fix_identifier")
      //state <- hCursor.get[String]("state")  // not used
      descr <- hCursor.get[String]("description")
      fix <- getFix(hCursor,id,descr)
    } yield {
      lastId = id
      addString(fix.name)
      fix.navaid.foreach(addString)

      fix
    }
  }

  def parseJson (input: String): Seq[Fix] = {
    val json: Json = parser.parse(input).getOrElse(Json.Null)
    val data: Option[Json] = json.hcursor.downField("data").focus

    data match {
      case Some(list) =>
        list.hcursor.as[List[Fix]] match {
          case Right(fixes) => fixes
          case Left(err) =>
            println(s"parse error after fix '$lastId'")
            Seq.empty[Fix]
        }
      case None => Seq.empty[Fix]
    }
  }
}


class FixDB (data: ByteBuffer) extends GisItemDB[Fix](data) {

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