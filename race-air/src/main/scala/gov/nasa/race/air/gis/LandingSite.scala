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

import gov.nasa.race.geo._
import gov.nasa.race.gis.{GisItem, GisItemDB, GisItemDBFactory}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.util.{FileUtils, LeDataOutputStream}

import scala.collection.Seq


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

  def lsType (t: Int) = t match {
    case LS_AIRPORT => "airport"
    case LS_HELIPORT => "heliport"
    case _ => "?"
  }

  def getLsType(s: String): Int = {
    if (s.equalsIgnoreCase("Airport")) LS_AIRPORT
    else if (s.equalsIgnoreCase("Heliport")) LS_HELIPORT
    else LS_UNDEFINED
  }

  //--- landing site access
  val ACC_UNDEFINED = -1
  val ACC_CIVIL = 1
  val ACC_MIL = 2
  val ACC_PRIVATE = 3

  def lsAccess (t: Int) = t match {
    case ACC_CIVIL => "civil"
    case ACC_MIL => "mil"
    case ACC_PRIVATE => "priv"
    case _ => "?"
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
                       ) extends GisItem {
  override def toString: String = {
    s"""LandingSite("$name",$pos,"$descr",${LandingSite.lsType(lsType)},${LandingSite.lsAccess(lsAccess)},$magVar})"""
  }

  override def addStrings (db: GisItemDBFactory[_]): Unit = {
    db.addString(name)
    db.addString(descr)
  }
}

class LandingSiteDB (data: ByteBuffer) extends GisItemDB[LandingSite](data) {

  def this (file: File) = this(GisItemDB.mapFile(file))

  override protected def readItem (off: Int): LandingSite = {
    val buf = data
    buf.position(off + 28) // skip over hash and xyz coords

    val lat = buf.getDouble
    val lon = buf.getDouble
    val elev = buf.getDouble
    val pos = GeoPosition(Degrees(lat),Degrees(lon),Feet(elev))

    val name = strings(buf.getInt)

    val descr = strings(buf.getInt)
    val lsType = buf.getInt
    val lsAccess = buf.getInt
    val magVar = buf.getFloat

    LandingSite(name,pos, descr,lsType,lsAccess,magVar)
  }
}

object LandingSiteDB extends GisItemDBFactory[LandingSite](72) {
  import LandingSite._

  val LandingSiteRE = """\s*INSERT\s+INTO\s+`LandingSite`\s+VALUES\s*\(\s*(\d+)\s*,\s*'(\w+)'\s*,\s*'(\w+)'\s*,\s*'([^']+)'\s*,\s*'(\w+)'\s*,\s*(\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\).*""".r

  override def loadDB (file: File): Option[LandingSiteDB] = {
    mapFile(file).map(new LandingSiteDB(_))
  }

  override def parse (inFile: File, extraArgs: Seq[String]): Boolean = {
    if (extraArgs.nonEmpty) println(s"extra arguments ignored: [${extraArgs.mkString(",")}]")

    if (!FileUtils.getExtension(inFile).equalsIgnoreCase("sql")) {
      println(s"not a SQL source: $inFile")
      return false
    }

    FileUtils.withLines(inFile) { line =>
      line match {
        case LandingSiteRE(id,cat,name,descr,access,elev,lat,lon,magVar) =>
          val pos = GeoPosition(Degrees(lat.toDouble), Degrees(lon.toDouble), Feet(elev.toDouble))

          val typeFlag = getLsType(cat)
          val accessFlag = getLsAccess(access)

          //--- populate the waypoint list
          addItem( LandingSite(name,pos,descr,typeFlag,accessFlag,magVar.toFloat))

        case _ => // ignore
      }
    }
    items.nonEmpty
  }

  override protected def writeItemPayloadFields(e: LandingSite, buf: ByteBuffer): Unit = {
    val descrIdx = strMap(e.descr)
    buf.putInt(descrIdx)
    buf.putInt(e.lsType)
    buf.putInt(e.lsAccess)
    buf.putFloat(e.magVar)
  }
}