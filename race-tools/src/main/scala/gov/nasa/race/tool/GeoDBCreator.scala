package gov.nasa.race.tool

import java.io.{DataOutputStream, File, FileOutputStream, OutputStream}

import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.FileUtils

import scala.collection.mutable.ArrayBuffer


/**
  * app to create Geo databases from SQL distributions of waypoint/airport/landingsite lists
  * that are distributed by FAA.
  *
  * The goals are
  *  (a) to avoid using a relational DB to access waypoints since they are static in nature (updated
  *      infrequently) and main access paths are by means of simple String id and/or nearest neighbor search
  *      (RDB is overkill for first and doesn't support last)
  *  (b) avoid heap allocation of respective objects (waypoints, airports) if they are not used. Our queries
  *      usually return only a small number and hence we do not need to load the heap with thousands of
  *      objects that also need to be instantiated before first use
  */
object GeoDBCreator {

  /* waypoint schemafor USA_FAA1801 :
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
   */

  val WaypointRE = """\s*INSERT\s+INTO\s+`Waypoint`\s+VALUES\s*\(\s*(\d+)\s*,\s*'(\w+)'\s*,\s*'(\w+)'\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*(-?\d+.\d+)\s*,\s*'(\w+)'\s*,\s*'?(NULL|\w+)'?\s*,\s*(NULL|\d+.\d+)\s*,\s*(NULL|\d+)\).*""".r

  //--- known waypoint types
  val WP_UNDEFINED = -1
  val WP_TERMINAL  = 1
  val WP_RUNWAY    = 2
  val WP_NAVAID    = 3

  //--- known navaid types
  val NAV_UNDEFINED = -1
  val NAV_LOC       = 1
  val NAV_NDB       = 2

  val UNDEFINED_ELEV = Double.NaN

  class Waypoint (name: String,
                  wpType: Int,
                  lat: Double,
                  lon: Double,
                  magVar: Double,
                  landingSite: Option[String],
                  navaidType: Int,
                  freq: Float,
                  elev: Double
                 ) {
  }

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var inFile: Option[File] = None
    var outDir: File = new File("tmp")

    requiredArg1("<pathName>", "SQL file to parse") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      ifSome(opts.inFile) { inFile =>
        val outFile = new File(opts.outDir, FileUtils.filenameWithExtension(inFile, "bin"))
        val out = new DataOutputStream(new FileOutputStream(outFile))
        try {
          parseSQL(inFile, out)
        } finally {
          out.close
        }
      }
    }
  }

  def parseSQL (inFile: File, out: DataOutputStream): Unit = {
    println(s"parsing input SQL: $inFile ..")
    val wpList = new ArrayBuffer[Waypoint]

    FileUtils.withLines(inFile) { line =>
      line match {
        case WaypointRE(id,name,wpType,lat,lon,magVar,ls,navaid,freq,elev) =>
          wpList += new Waypoint(name,getWpType(wpType),lat.toDouble,lon.toDouble,magVar.toDouble,
                                 getLandingSite(ls),getNavaid(navaid),getFreq(freq),getElev(elev))
        case _ => // ignore
      }
    }

    println(s"parsed ${wpList.size} waypoints")
  }

  def getWpType (wpType: String): Int = {
    wpType match {
      case "TERMINAL" => WP_TERMINAL
      case "RUNWAY" => WP_RUNWAY
      case "NAVAID" => WP_NAVAID
      case other =>
        println(s"unknown waypoint type: $other")
        WP_UNDEFINED
    }
  }

  def getLandingSite (ls: String): Option[String] = {
    if (ls.equalsIgnoreCase("NULL")) None else Some(ls)
  }

  def getNavaid (navaid: String): Int = {
    navaid match {
      case "NULL" => NAV_UNDEFINED
      case "LOC" => NAV_LOC
      case "NDB" => NAV_NDB
      case other =>
        println(s"unknown navaid type: $other")
        NAV_UNDEFINED
    }
  }

  def getFreq (freq: String): Float = {
    if (freq.equalsIgnoreCase("NULL")) 0.0f else freq.toFloat
  }

  def getElev (elev: String): Double = {
    if (elev.equalsIgnoreCase("NULL")) UNDEFINED_ELEV else elev.toDouble
  }
}
