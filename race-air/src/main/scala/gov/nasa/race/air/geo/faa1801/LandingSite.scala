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
import gov.nasa.race.util.FileUtils
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._


/**
  *  LandingSite schema for USA_FAA1801:
  *    `id` INT NULL,
  *    `category` VARCHAR(20) NOT NULL DEFAULT 'Airport',   Airport | Heliport
  *    `cid` VARCHAR(10) NOT NULL,                          e.g. KSFO
  *    `name` VARCHAR(45) NOT NULL,
  *    `access` VARCHAR(10) NOT NULL,                       Civil | Military | Private
  *    `elevation` INT NOT NULL,
  *    `latitude` FLOAT NOT NULL,
  *    `longitude` FLOAT NOT NULL,
  *    `magvar` FLOAT NOT NULL,
  *
  *     PRIMARY KEY (`id`),
  *     UNIQUE INDEX `cid_UNIQUE` (`cid` ASC),
  *     INDEX `category` (`category` ASC)  )
  */
object LandingSite {

  //--- landing site categories
  val LS_UNDEFINED = -1
  val LS_AIRPORT = 1
  val LS_HELIPORT = 2

  //--- landing site access
  val ACC_UNDEFINED = -1
  val ACC_CIVIL = 1
  val ACC_MIL = 2
  val ACC_PRIVATE = 3

  def getLsType(s: String): Int = {
    if (s.equalsIgnoreCase("Airport")) LS_AIRPORT
    else if (s.equalsIgnoreCase("Heliport")) LS_HELIPORT
    else LS_UNDEFINED
  }

  def getLsAccess(s: String): Int = {
    if (s.equalsIgnoreCase("Civil")) ACC_CIVIL
    else if (s.equalsIgnoreCase("Military")) ACC_MIL
    else if (s.equalsIgnoreCase("Private")) ACC_PRIVATE
    else ACC_UNDEFINED
  }
}

case class LandingSite (name: String, // cid
                        pos: GeoPosition,
                        descr: String,
                        lsType: Int,
                        lsAccess: Int,
                        magVar: Float
                       ) extends GisItem

class LandingSiteDB (data: ByteBuffer) extends GisItemDB[LandingSite](data) {

  override protected def readItem (off: Int): LandingSite = {
    val buf = data
    buf.position(off + 4) // skip over the hash

    val name = stringTable(buf.getInt)

    val lat = buf.getDouble
    val lon = buf.getDouble
    val elev = buf.getDouble
    val pos = GeoPosition(Degrees(lat),Degrees(lon),Feet(elev))

    val descr = stringTable(buf.getInt)
    val lsType = buf.getInt
    val lsAccess = buf.getInt
    val magVar = buf.getFloat

    LandingSite(name,pos, descr,lsType,lsAccess,magVar)
  }
}

class LandingSiteDBFactory extends GisItemDBFactory[LandingSite] {
  import LandingSite._

  val LandingSiteRE = """\s*INSERT\s+INTO\s+`LandingSite`\s+VALUES\s*\(\s*(\d+)\s*,\s*'(\w+)'\s*,\s*'(\w+)'\s*,\s*'([^']+)'\s*,\s*'(\w+)'\s*,\s*(\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\).*""".r

  override val schema = "gov.nasa.race.air.geo.faa1801.LandingSite"
  override val itemSize: Int = 44

  override def loadDB (file: File): Option[LandingSiteDB] = {
    mapFile(file).map(new LandingSiteDB(_))
  }

  override def parse (inFile: File): Unit = {
    clear
    addString(schema)

    FileUtils.withLines(inFile) { line =>
      line match {
        case LandingSiteRE(id,cat,name,descr,access,elev,lat,lon,magVar) =>
          if (items.size > 10) return

          val pos = GeoPosition(Degrees(lat.toDouble), Degrees(lon.toDouble), Feet(elev.toDouble))

          val typeFlag = getLsType(cat)
          val accessFlag = getLsAccess(access)

          addString(name)
          addString(descr)

          //--- populate the waypoint list
          addItem( LandingSite(name,pos,descr,typeFlag,accessFlag,magVar.toFloat))
        case _ => // ignore
      }
    }
  }

  override protected def writeItem(e: LandingSite, out: DataOutputStream): Unit = {
    writeCommonItemFields(e, out)

    val descrIdx = strMap(e.descr)
    out.writeInt(descrIdx)
    out.writeInt(e.lsType)
    out.writeInt(e.lsAccess)
    out.writeFloat(e.magVar)
  }
}