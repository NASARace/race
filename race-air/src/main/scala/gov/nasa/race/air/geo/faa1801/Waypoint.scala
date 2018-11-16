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
package gov.nasa.race.air.geo.faa1801

import java.io.{DataOutputStream, File}
import java.nio.ByteBuffer

import gov.nasa.race.geo._
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.util.FileUtils


/**
  *  Waypoint schema for USA_FAA1801 :
  *
  *    `id` INT NOT NULL,
  *    `type` VARCHAR(10) NOT NULL,             TERMINAL|RUNWAY|NAVAID
  *    `name` VARCHAR(10) NOT NULL,
  *    `latitude` FLOAT NOT NULL,
  *    `longitude` FLOAT NOT NULL,
  *    `magVar` FLOAT NOT NULL,
  *    `LandingSite_cid` VARCHAR(10) NULL,
  *    `navaidType` VARCHAR(10) NULL,           LOC|NDB
  *    `frequency` FLOAT NULL,
  *    `elevation` INT NULL,
  *
  *     PRIMARY KEY (`id`),
  *     INDEX `name_idx` (`name` ASC),
  *     UNIQUE INDEX `unique_idx` (`name` ASC, `latitude` ASC, `longitude` ASC, `navaidType` ASC, `LandingSite_cid` ASC),
  *     INDEX `landingsite_cid_idx` (`LandingSite_cid` ASC))
  */
object Waypoint {

  //--- waypoint categories
  val WP_UNDEFINED = -1
  val WP_TERMINAL = 1
  val WP_RUNWAY = 2
  val WP_NAVAID = 3

  //--- known navaid types
  val NAV_UNDEFINED = -1
  val NAV_LOC = 1
  val NAV_NDB = 2

  val UNDEFINED_ELEV = Float.NaN

  def getWpType(wpType: String): Int = {
    wpType match {
      case "TERMINAL" => WP_TERMINAL
      case "RUNWAY" => WP_RUNWAY
      case "NAVAID" => WP_NAVAID
      case other =>
        println(s"unknown waypoint type: $other")
        WP_UNDEFINED
    }
  }

  def getWpLandingSite(ls: String): Option[String] = {
    if (ls.equalsIgnoreCase("NULL")) None else Some(ls)
  }

  def getWpNavaid(navaid: String): Int = {
    navaid match {
      case "NULL" => NAV_UNDEFINED
      case "LOC" => NAV_LOC
      case "NDB" => NAV_NDB
      case other =>
        println(s"unknown navaid type: $other")
        NAV_UNDEFINED
    }
  }

  def getWpFreq(freq: String): Float = {
    if (freq.equalsIgnoreCase("NULL")) 0.0f else freq.toFloat
  }

  def getWpElev(elev: String): Double = {
    if (elev.equalsIgnoreCase("NULL")) UNDEFINED_ELEV else elev.toDouble
  }
}

/**
  * note - we keep fields as vars so that we can use cache objects for queries
  * (items should not be stored outside the DB anyways)
  */
case class Waypoint(name: String,
                    pos: GeoPosition,
                    wpType: Int,
                    magVar: Float,
                    landingSite: Option[String],
                    navaidType: Int,
                    freq: Float
                   ) extends GisItem

class WaypointDB (data: ByteBuffer) extends GisItemDB[Waypoint](data) {

  override protected def readItem (off: Int): Waypoint = {
    val buf = data
    buf.position(off + 4) // skip over the hash

    val name = strings(data.getInt)

    val lat = buf.getDouble
    val lon = buf.getDouble
    val elev = buf.getDouble
    val pos = GeoPosition(Degrees(lat),Degrees(lon),Feet(elev))

    val wpType = buf.getInt
    val magVar = buf.getFloat
    val landingSite = {
      val idx = buf.getInt
      if (idx >= 0) Some(strings(idx)) else None
    }
    val navaidType = buf.getInt
    val freq = buf.getFloat

    Waypoint(name, pos, wpType,magVar,landingSite,navaidType,freq)
  }
}

class WaypointDBFactory extends GisItemDBFactory[Waypoint] {
  import Waypoint._

  val WaypointRE =
    """\s*INSERT\s+INTO\s+`Waypoint`\s+VALUES\s*\(\s*(\d+)\s*,\s*'(\w+)'\s*,\s*'(\w+)'\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*'(\w+)'\s*,\s*'?(NULL|\w+)'?\s*,\s*(NULL|\d+.\d+)\s*,\s*(NULL|\d+)\).*""".r

  override val schema = "gov.nasa.race.air.geo.faa1801.Waypoint"
  override val itemSize: Int = 56

  override def loadDB (file: File): Option[WaypointDB] = {
    mapFile(file).map(new WaypointDB(_))
  }

  override def parse (inFile: File): Unit = {
    clear
    addString(schema)

    FileUtils.withLines(inFile) { line =>
      line match {
        case WaypointRE(id,name,wpType,lat,lon,magVar,landingSite,navaid,freq,elev) =>

          val pos = GeoPosition(Degrees(lat.toDouble), Degrees(lon.toDouble), Feet(getWpElev(elev)))

          val typeFlag = getWpType(wpType)
          val navaidFlag = getWpNavaid(navaid)
          val optLandingSite = getWpLandingSite(landingSite)

          addString(name)
          addString(landingSite)

          //--- populate the waypoint list
          addItem( new Waypoint(name,pos,typeFlag,magVar.toFloat, optLandingSite,navaidFlag,getWpFreq(freq)))
        case _ => // ignore
      }
    }
  }

  override protected def writeItem(e: Waypoint, out: DataOutputStream): Unit = {
    writeCommonItemFields(e, out)

    val lsIdx = if (e.landingSite.isDefined) strMap(e.landingSite.get) else -1

    out.writeInt(e.wpType)
    out.writeFloat(e.magVar)
    out.writeInt(lsIdx)
    out.writeInt(e.navaidType)
    out.writeFloat(e.freq)
  }
}