package gov.nasa.race.air.geo.faa1801

import java.io.{DataOutputStream, File, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import gov.nasa.race.geo.{GisItem, GisItemDB, GisItemDBFactory}
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

  def getWpElev(elev: String): Float = {
    if (elev.equalsIgnoreCase("NULL")) UNDEFINED_ELEV else elev.toFloat
  }
}

/**
  * note - we keep fields as vars so that we can use cache objects for queries
  * (items should not be stored outside the DB anyways)
  */
case class Waypoint(name: String,
                    lat: Double,
                    lon: Double,
                    wpType: Int,
                    magVar: Float,
                    landingSite: Option[String],
                    navaidType: Int,
                    freq: Float,
                    elev: Float
                   ) extends GisItem {
  val hash = name.hashCode // store to avoid re-computation
}


class WaypointDB (data: ByteBuffer) extends GisItemDB[Waypoint](data) {

  override protected def readItem (off: Int): Waypoint = {
    val buf = data
    buf.position(off + 4) // skip over the hash

    val name = stringTable(data.getInt)
    val lat = buf.getDouble
    val lon = buf.getDouble

    val wpType = buf.getInt
    val magVar = buf.getFloat
    val landingSite = {
      val idx = buf.getInt
      if (idx >= 0) Some(stringTable(idx)) else None
    }
    val navaidType = buf.getInt
    val freq = buf.getFloat
    val elev = buf.getFloat

    Waypoint(name,lat,lon,wpType,magVar,landingSite,navaidType,freq,elev)
  }
}

class WaypointDBFactory extends GisItemDBFactory[Waypoint] {
  import Waypoint._

  val WaypointRE =
    """\s*INSERT\s+INTO\s+`Waypoint`\s+VALUES\s*\(\s*(\d+)\s*,\s*'(\w+)'\s*,\s*'(\w+)'\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*'(\w+)'\s*,\s*'?(NULL|\w+)'?\s*,\s*(NULL|\d+.\d+)\s*,\s*(NULL|\d+)\).*""".r

  override val schema = "gov.nasa.race.air.geo.faa1801.Waypoint"
  override val itemSize: Int = 44

  override def loadDB (file: File): Option[WaypointDB] = {
    mapFile(file).map(new WaypointDB(_))
  }

  override def parse (inFile: File): Unit = {
    clear
    addString(schema)

    FileUtils.withLines(inFile) { line =>
      line match {
        case WaypointRE(id,name,wpType,lat,lon,magVar,landingSite,navaid,freq,elev) =>
          //--- populate the string map
          // it appears RUNWAY and 2-letter NAVAID/NDB names are not unique
          val typeFlag = getWpType(wpType)
          val navaidFlag = getWpNavaid(navaid)
          val optLandingSite = getWpLandingSite(landingSite)

          addString(name)
          addString(landingSite)

          //--- populate the waypoint list
          addItem( new Waypoint(name,lat.toDouble,lon.toDouble,typeFlag,magVar.toFloat,
                                          optLandingSite,navaidFlag,getWpFreq(freq),getWpElev(elev)))
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
    out.writeFloat(e.elev)
  }
}