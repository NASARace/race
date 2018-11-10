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
package gov.nasa.race.geo

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Arrays

import gov.nasa.race.common._
import gov.nasa.race.util.{ArrayUtils, FileUtils}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * a static GisItem database that supports the following types of queries:
  *
  *  - name lookup (name being a unique alphanumeric key)
  *  - nearest item for given location
  *  - sorted list of N nearest items for given location
  *  - all items within a given distance of given location
  *
  * The goal is to load such DBs from mmapped files that only contain primitive data (char, int, double,..) and
  * relative references (stored as index values) so that the JVM heap footprint is minimal and DB files can
  * be used from different languages.
  *
  * the memory layout/file format of GisItemDBs is as follows:
  *
  * struct RGIS {
  *   i32 magic             // 1380403539 0x52474953 (RGIS)
  *
  *   //--- string table  (first entry is schema name, e.g. "gov.nasa.race.air.LandingSite")
  *   i32 nStrings          // number of entries in string table
  *   struct StrEntry {
  *     i32 dataLen         // in bytes
  *     i32 dataOffset      // index into strData (local, i.e. 0-based)
  *   } [nStrings]
  *   i32 nChars            // number of bytes in strData
  *   char strData[nChars]  // mod utf8 bytes (without terminating 0)
  *
  *   //--- entry list
  *   i32 nItems            // number of GisItems in this DB
  *   i32 itemSize          // in bytes
  *   struct Item {         //  the payload data
  *     i32 hashCode        // for main id (name)
  *     f64 x, y, z         // ECEF coords
  *     f64 lat, lon, alt   // geodetic coords
  *     i32 id              // index into string list
  *     ...                 // other payload fields - all string references replaced by string list indices
  *   } [nItems]
  *
  *   //--- key map (name -> entry)
  *   i32 mapLength         // number of slots for open hashing table
  *   i32 mapRehash         // number to re-compute hash index
  *   i32 mapItems [mapLength] // item list offset (-1 if free slot)
  *
  *   //--- kd-tree
  *   struct Node {
  *     i32 entry           // item list offset for node data
  *     i32 leftChild       // Node offset of left child (-1 if none)
  *     i32 rightChild      // Node offset of right child (-1 if none)
  *   } [nItems]
  * }
  *
  * total size of structure in bytes:
  *   4 + (8 + nStrings*8 + nChars) + (8 + nItems*sizeOfItem) + (8 + mapItems*4) + (nItems * 12)
  *
  *
  * TODO - should explicitly specify Charset to use
  */

object GisItemDB {
  val MAGIC = 0x52474953 //  "RGIS"

  def mapFile(file: File): ByteBuffer = {
    val fileChannel = new RandomAccessFile(file, "r").getChannel
    fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size)
  }


  @inline def intToUnsignedLong (i: Int): Long = {
    0x00000000ffffffffL & i
  }

  @inline def nextIndex (idx: Int, h: Int, mapLength: Int, rehash: Int): Int = {
    val h2 = (1 + (intToUnsignedLong(h)) % rehash).toInt
    (idx + h2) % mapLength
  }

  val EMPTY = -1

  //--- kdtree support

  trait KdResult {
    def canContain (d: Double): Boolean
    def update (d: Double, nOff: Int): Unit
    def computeDist (lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double
    def getDistance: Double
  }

  class CartesianNearestNeighbor extends KdResult {
    var nodeOff: Int = -1
    var dist: Double = Double.MaxValue  // can be any value that supports ordering (not only absolute distance)

    def canContain (d: Double): Boolean = d < dist

    def update (d: Double, nOff: Int): Unit = {
      if (d < dist) {
        dist = d
        nodeOff = nOff
      }
    }

    def computeDist (lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double = {
      squared(lat1 - lat0) + squared(lon1 - lon0)
    }

    def getDistance: Double = Math.sqrt(dist)
  }
}

abstract class GisItemDB[T <: GisItem] (data: ByteBuffer) {
  import GisItemDB._

  def this (f: File) = this(GisItemDB.mapFile(f))

  //--- initialize stringtable, offsets and sizes

  val nStrings: Int = data.getInt(4)
  val nChars: Int = data.getInt(8 + nStrings*8)
  val charDataOffset: Int = 8 + nStrings*8 + 4
  protected val stringTable: Array[String] = createStringTable // we always instantiate Strings

  val itemOffset: Int = charDataOffset + nChars + 8
  val nItems: Int = data.getInt(itemOffset - 8)
  val itemSize: Int = data.getInt(itemOffset - 4)

  val mapOffset: Int = itemOffset + (nItems * itemSize) + 8
  val mapLength: Int = data.getInt(mapOffset - 8)
  val mapRehash: Int = data.getInt(mapOffset - 4)

  val kdOffset: Int = mapOffset + (mapLength * 4)

  def createStringTable: Array[String] = {
    val a = new Array[String](nStrings)
    var buf = new Array[Byte](256)

    for (i <- 0 until nStrings) {
      val recOff = 8 + i*8
      val dataLen = data.getInt(recOff)
      val dataOff = data.getInt(recOff+4)

      if (dataLen > buf.length) buf = new Array[Byte](dataLen)
      data.position(dataOff)
      data.get(buf, 0, dataLen)
      a(i) = new String(buf,0, dataLen)
    }
    a
  }

  //--- testing & debugging

  def stringIterator: Iterator[String] = stringTable.iterator

  def printStructure: Unit = {
    println("--- structure:")
    println(s"schema:      '${stringTable(0)}'")

    println(s"data size:   ${data.limit()}")
    println(s"nItems:      $nItems")
    println(s"itemSize:    $itemSize")

    println(s"nStrings:    $nStrings")
    println(s"nChars:      $nChars")
    println(s"mapLength:   $mapLength")

    println(s"char offset: $charDataOffset")
    println(s"item offset: $itemOffset")
    println(s"map offset:  $mapOffset")
    println(s"kd offset:   $kdOffset")
  }

  def printStrings: Unit = {
    println("--- string table:")
    for ((s,i) <- stringTable.zipWithIndex){
      println(f"$i%5d: '$s'")
    }
  }

  def printItems: Unit = {
    var off = itemOffset
    println("--- items:")
    for (i <- 0 until nItems){
      val e = readItem(off)
      println(f"$i%5d: $e")
      off += itemSize
    }
  }

  //--- queries

  def size: Int = nItems

  def isEmpty: Boolean = nItems > 0

  /**
    * to be provided by concrete class - turn raw data into object
    * iIdx is guaranteed to be within 0..nItems
    */
  protected def readItem (iIdx: Int): T


  //--- key map

  /**
    * alphanumeric key lookup
    */
  def getItem (key: String): Option[T] = {
    val buf = data
    val h = key.hashCode
    var idx = (intToUnsignedLong(h) % mapLength).toInt

    var i = 0
    while (i < nItems) {
      val eOff = buf.getInt(mapOffset + (idx * 4))
      if (eOff == EMPTY) return None

      if (buf.getInt(eOff) == h) {
        val k = stringTable(buf.getInt(eOff + 52))
        if (k.equals(key)) return Some(readItem(eOff))
      }

      // hash collision
      idx = nextIndex(idx, h, mapLength, mapRehash)
      i += 1
    }

    throw new RuntimeException(s"entry $key not found in $i iterations - possible map corruption")
  }


  //--- kdtree

  protected def searchKd (lat: Double, lon: Double, depth: Int, res: KdResult): Unit = {

  }

  /**
    * nearest neighbor search
    */
  def getNearestItem (lat: Double, lon: Double): Option[T] = {
    if (!isEmpty) {
      None // TODO
    } else None
  }
}

object GisItemDBFactory {

  //-- open hashing support for writing item key maps

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

  def getMapConst (nItems: Int): (Int,Int,Int) = {
    for (e <- sizeTable) {
      if (e._1 >= nItems) return e
    }
    throw new RuntimeException("too many entries")
  }
}


/**
  * objects that can create concrete GisItemDBs
  */
abstract class GisItemDBFactory[T <: GisItem] {

  val strMap = new mutable.LinkedHashMap[String,Int]
  val items  = new ArrayBuffer[T]
  val xyzPos = new ArrayBuffer[XyzPos]

  var itemOffset = 0  // byte offset if item0

  //--- to be provided by concrete class
  val schema: String
  val itemSize: Int // in bytes
  protected def parse (inFile: File): Unit
  protected def writeItem (it: T, dos: DataOutputStream): Unit

  def createDB (inFile: File, outFile: File): Boolean = {
    if (FileUtils.existingNonEmptyFile(inFile).isDefined){
      if (FileUtils.ensureWritable(outFile).isDefined){
        parse(inFile)
        if (!items.isEmpty) {
          write(outFile)
          true
        } else {
          println(s"no $schema items found in file: $inFile")
          false
        }
      } else {
        println(s"invalid output file: $inFile")
        false
      }
    } else {
      println(s"invalid input file: $inFile")
      false
    }
  }

  def loadDB (file: File): Option[GisItemDB[T]]

  //--- build support

  protected def mapFile (file: File): Option[ByteBuffer] = {
    if (file.isFile) {
      try {
        val len = file.length
        val fc = new RandomAccessFile(file, "r").getChannel
        Some(fc.map(FileChannel.MapMode.READ_ONLY, 0, len))
      } catch {
        case x: Throwable =>
          println(s"error mapping rgis $file: $x")
          None
      }
    } else {
      println(s"rgis file not found: $file")
      None
    }
  }

  protected def clear: Unit = {
    strMap.clear()
    items.clear()
  }

  protected def addString (s: String): Boolean = {
    val n = strMap.size
    strMap.getOrElseUpdate(s, n)
    strMap.size > n
  }

  protected def addItem (e: T): Unit = {
    items += e
    xyzPos += e.ecef
  }

  def write (outFile: File): Unit = {
    val fos = new FileOutputStream(outFile, false)
    val dos = new DataOutputStream(fos)

    dos.writeInt(GisItemDB.MAGIC)

    writeStrMap(dos)
    writeItems(dos)
    writeKeyMap(dos)
    writeKdTree(dos)

    dos.close
  }

  protected def writeStrMap (dos: DataOutputStream): Unit = {
    val charData = new ByteArrayOutputStream
    var pos = 8 + (strMap.size * 8) + 4

    dos.writeInt(strMap.size)
    for (e <- strMap) {
      val s = e._1
      val b = s.getBytes
      charData.write(b)

      dos.writeInt(b.length)
      dos.writeInt(pos)

      pos += b.length
    }

    dos.writeInt(charData.size)
    charData.writeTo(dos)
  }

  protected def writeItems (dos: DataOutputStream): Unit = {
    dos.writeInt(items.size)
    dos.writeInt(itemSize)

    itemOffset = dos.size
    items.foreach { it => writeItem(it, dos) }
  }

  protected def writeCommonItemFields (e: T, dos: DataOutputStream): Unit = {
    val nameIdx = strMap(e.name)
    val pos = e.pos
    val ecef = e.ecef

    dos.writeInt(e.hash)
    dos.writeDouble(ecef.xMeters)
    dos.writeDouble(ecef.yMeters)
    dos.writeDouble(ecef.zMeters)

    dos.writeDouble(pos.latDeg)
    dos.writeDouble(pos.lonDeg)
    dos.writeDouble(pos.altMeters)

    dos.writeInt(nameIdx)
  }

  protected def writeKeyMap (dos: DataOutputStream): Unit = {
    import GisItemDBFactory._
    import GisItemDB._

    val mapConst = getMapConst(items.size)
    val mapLength = mapConst._2
    val rehash = mapConst._3

    val slots = new Array[Int](mapLength)
    Arrays.fill(slots,EMPTY)

    def addEntry (eIdx: Int, h: Int): Unit = {
      var idx = (intToUnsignedLong(h) % mapLength).toInt
      while (slots(idx) != EMPTY) {  // hash collision
        idx = nextIndex(idx,h,mapLength,rehash)
      }

      slots(idx) = itemOffset + (eIdx * itemSize)
    }

    for ((e,i) <- items.zipWithIndex) {
      addEntry(i,e.hash)
    }

    dos.writeInt(mapLength)
    dos.writeInt(rehash)
    slots.foreach(dos.writeInt)
  }

  protected def writeKdTree (dos: DataOutputStream): Unit = {
    val orderings = Array(
      new Ordering[Int]() { def compare(eIdx1: Int, eIdx2: Int) = xyzPos(eIdx1).x compare xyzPos(eIdx2).x },
      new Ordering[Int]() { def compare(eIdx1: Int, eIdx2: Int) = xyzPos(eIdx1).y compare xyzPos(eIdx2).y },
      new Ordering[Int]() { def compare(eIdx1: Int, eIdx2: Int) = xyzPos(eIdx1).z compare xyzPos(eIdx2).z }
    )

    val nItems = items.size
    val node0Offset = dos.size

    //--- input array with entryList indices
    val eIdxs = new Array[Int](nItems)
    for (i <- 0 until nItems) eIdxs(i) = i

    var n = 0
    val itemIdx = new Array[Int](nItems)
    val leftNode = new Array[Int](nItems)
    val rightNode = new Array[Int](nItems)

    //--- output buffer representing the kdtree nodes
    //    each entry consists of a (Int,Int,Int) tuple: (item offset, left node child off, right node child off)

    def kdTree (i0: Int, i1: Int, depth: Int): Int = {
      if (i0 >= i1) {
        -1

      } else {
        ArrayUtils.quickSort(eIdxs, i0, i1)(orderings(depth % 3))

        val median = (i1 + i0) / 2
        val pivot = eIdxs(median)
        val nodeIdx = n
        n += 1

        itemIdx(nodeIdx) = pivot
        leftNode(nodeIdx)  = kdTree( i0, median-1, depth+1)
        rightNode(nodeIdx) = kdTree( median+1, i1, depth+1)

        nodeIdx
      }
    }

    def nodeOffset (idx: Int): Int = if (idx == -1) -1 else node0Offset + idx * 12    // 3 Int per node

    kdTree(0, nItems-1, 0)

    for (i <- 0 until nItems) {
      dos.writeInt( itemOffset + (itemIdx(i) * itemSize)) // offset of node item
      dos.writeInt( nodeOffset(leftNode(i)))
      dos.writeInt( nodeOffset(rightNode(i)))
    }
  }
}