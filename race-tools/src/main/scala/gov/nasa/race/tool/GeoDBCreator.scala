package gov.nasa.race.tool

import java.io._
import java.nio.ByteBuffer

import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.FileUtils

import scala.collection.mutable.LinkedHashMap
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
  *
  * The layout of the resulting binary is as follows:
  * (all offsets are in bytes from beginning of struct)
  *
  * struct xGis {
  *   i32 strOffset     // string list offset
  *   i32 entryOffset   // entry list offset
  *   i32 mapOffset     // id map offset
  *   i32 kdOffset      // kd tree offset
  *
  *   //--- string list
  *   i32 nStrings      // number of entries in string table
  *   struct StrEntry { // (const size)
  *     i32 dataOffset
  *     i32 dataLen
  *   } [nStrings]
  *   char strData[]
  *
  *   //--- entry list
  *   i32 nEntries      // number of waypoint entries
  *   i32 sizeOfEntry   // in bytes
  *   struct Entry {  // (const size)
  *     i32 hashCode    // for main id (name)
  *     i32 id          // index into string list
  *     f64 lat
  *     f64 lon
  *     ...             // other payload fields - all string references replaced by string list indices
  *   } [nEntries]
  *
  *   //--- id hashmap (name -> entry)
  *   i32 mapSize       // number of slots for open hashing table
  *   i32 mapEntries [mapSize] // index into entry list
  * }
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

  val UNDEFINED_ELEV = Float.NaN

  case class Waypoint(name: String,
                      lat: Double,
                      lon: Double,
                      wpType: Int,
                      magVar: Float,
                      landingSite: Option[String],
                      navaidType: Int,
                      freq: Float,
                      elev: Float
                 ) {
    val hash = name.hashCode // store to avoid recomputation
  }

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var inFile: Option[File] = None
    var outDir: File = new File("tmp")

    requiredArg1("<pathName>", "SQL file to parse") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  val entryList = new ArrayBuffer[Waypoint]
  val strMap = new LinkedHashMap[String,Int]


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

  // we need to base this on the mod-UTF bytes since we might have to compute this from native clients
  def hash (s: String): Int = {

  }

  def parseSQL (inFile: File, out: DataOutputStream): Unit = {
    println(s"parsing input SQL: $inFile ..")

    FileUtils.withLines(inFile) { line =>
      line match {
        case WaypointRE(id,name,wpType,lat,lon,magVar,landingSite,navaid,freq,elev) =>
          //--- populate the string map
          addString(name)
          addString(landingSite)

          //--- populate the waypoint list
          entryList += new Waypoint(name,lat.toDouble,lon.toDouble,getWpType(wpType),magVar.toFloat,
                                 getLandingSite(landingSite),getNavaid(navaid),getFreq(freq),getElev(elev))
        case _ => // ignore
      }
    }

    println(s"parsed ${entryList.size} waypoints")

    ///////////////////////////////////// test
    val strSeg = writeStrList(0)
    println(s"@@ str: ${strSeg.size}")

    val entrySeg = writeEntryList(0)
    println(s"@@ ent: ${entrySeg.size}")

    val strList = readStrList(ByteBuffer.wrap(strSeg.toByteArray),0)
    val wp0 = readEntry(ByteBuffer.wrap(entrySeg.toByteArray), 8, strList)
    println(s"@@ e[0]: $wp0")
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

  def getElev (elev: String): Float = {
    if (elev.equalsIgnoreCase("NULL")) UNDEFINED_ELEV else elev.toFloat
  }

  //-------------------------------------------------------- IO functions

  //--- string list

  def writeStrList(off0: Int): ByteArrayOutputStream = {
    val dataBuf = new ByteArrayOutputStream
    val strDataOut = new DataOutputStream(dataBuf)

    val strBuf = new ByteArrayOutputStream
    val strListOut = new DataOutputStream(strBuf)

    val d0 = off0 + 4 + strMap.size * 8  // off0 + nStrings + nStrings * (dataOffset,dataLen)

    strListOut.writeInt(strMap.size)

    for (e <- strMap) {
      val dataOffset = strDataOut.size
      strDataOut.writeBytes(e._1)
      strListOut.writeInt(d0 + dataOffset)
      strListOut.writeInt(strDataOut.size - dataOffset) // mod UTF length of string
    }

    dataBuf.writeTo(strBuf)
    strBuf
  }


  def readStrList (buf: ByteBuffer, off0: Int): Array[String] = {
    val nStrings = buf.getInt(off0)
    var j = off0 + 4
    val a = new Array[String](nStrings)
    var bb = new Array[Byte](256)

    for (i <- 0 until nStrings) {
      val dataOff = buf.getInt(j)
      val dataLen = buf.getInt(j+4)

      if (dataLen > bb.length) bb = new Array[Byte](dataLen)
      buf.position(dataOff)
      buf.get(bb,0,dataLen)
      val s = new String(bb, 0, dataLen)
      a(i) = s
      println(s"@@@ $i: $s")

      j += 8
    }

    a
  }

  //--- entry list

  def writeEntryList (off0: Int): ByteArrayOutputStream = {
    val eBuf = new ByteArrayOutputStream
    val eOut = new DataOutputStream(eBuf)

    eOut.writeInt(entryList.size)
    eOut.writeInt(44)    // sizeof Wp entry

    for (e: Waypoint <- entryList) {
      val nameIdx = strMap(e.name)
      val lsIdx = if (e.landingSite.isDefined) strMap(e.landingSite.get) else -1

      eOut.writeInt(nameIdx)
      eOut.writeDouble(e.lat)
      eOut.writeDouble(e.lon)
      eOut.writeInt(e.wpType)
      eOut.writeFloat(e.magVar)
      eOut.writeInt(lsIdx)
      eOut.writeInt(e.navaidType)
      eOut.writeFloat(e.freq)
      eOut.writeFloat(e.elev)
    }

    eBuf
  }

  def readEntry (buf: ByteBuffer, off: Int, strList: Array[String]): Waypoint = {
    buf.position(off)

    val name = strList(buf.getInt)
    val eType = buf.getInt
    val lat = buf.getDouble
    val lon = buf.getDouble
    val magVar = buf.getFloat
    val landingSite = {
      val idx = buf.getInt
      if (idx >= 0) Some(strList(idx)) else None
    }
    val navaid = buf.getInt
    val freq = buf.getFloat
    val elev = buf.getFloat

    Waypoint(name,lat,lon,eType,magVar,landingSite,navaid,freq,elev)
  }

  //--- id hashmap

  val sizeTable = Array(
    // max entries   size     rehash
    (       8,         13,        11 ),
    (      16,         19,        17 ),
    (      32,         43,        41 ),
    (      64,         73,        71 ),
    (     128,        151,       149 ),
    (     256,        283,       281 ),
    (     512,        571,       569 ),
    (    1024,       1153,      1151 ),
    (    2048,       2269,      2267 ),
    (    4096,       4519,      4517 ),
    (    8192,       9013,      9011 ),
    (   16384,      18043,     18041 ),
    (   32768,      36109,     36107 ),
    (   65536,      72091,     72089 ),
    (  131072,     144409,    144407 ),
    (  262144,     288361,    288359 ),
    (  524288,     576883,    576881 ),
    ( 1048576,    1153459,   1153457 ),
    ( 2097152,    2307163,   2307161 ),
    ( 4194304,    4613893,   4613891 ),
    ( 8388608,    9227641,   9227639 ),
    (16777216,   18455029,  18455027 )
  )

  def getMapConst (nEntries: Int): (Int,Int,Int) = {
    for (e <- sizeTable) {
      if (e._1 >= nEntries) return e
    }
    throw new RuntimeException("too many entries")
  }

  def writeIdMap (off0: Int): ByteArrayOutputStream = {
    val mapConst = getMapConst(entryList.size)


    null
  }
}