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
package gov.nasa.race.gis

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Arrays
import java.util.zip.CRC32

import gov.nasa.race.common._
import gov.nasa.race.geo._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.util.{ArrayUtils, FileUtils}
import org.joda.time.DateTime

import scala.Double.{MaxValue => DMax, MinValue => DMin}
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
  *   i64 length            // byte length of whole structure
  *   i64 checksum          // CRC32 checksum
  *   i64 date              // epoch in millis
  *
  *   //--- string table  (first entry is schema name, e.g. "gov.nasa.race.air.LandingSite")
  *   i32 nStrings          // number of entries in string table
  *   struct StrEntry {
  *     i32 strLen             // in bytes, without terminating 0
  *     char strBytes[strLen]  // string bytes (mod-UTF8), with terminating 0
  *   } [nStrings]
  *
  *   //--- entry list
  *   i32 nItems            // number of GisItems in this DB
  *   i32 itemSize          // in bytes
  *   struct Item {         // the payload data
  *     i32 hashCode        // for main id (name)
  *     f64 x, y, z         // ECEF coords
  *     f64 lat, lon, alt   // geodetic coords
  *     i32 id              // string index (NOT offset)  : 56 bytes
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
  val HEADER_LENGTH = 28  // magic + file-length + checksum + date

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


  final val ME = 6371000.0
  final val ME2 = ME * ME
}

abstract class GisItemDB[T <: GisItem] (data: ByteBuffer) {
  import GisItemDB._

  def this (f: File) = this(GisItemDB.mapFile(f))

  if (data.getInt(0) != MAGIC) throw new RuntimeException("invalid file magic (should be 'RGIS')")

  val length = data.getLong(4)
  if (data.limit() != length) throw new RuntimeException(s"invalid data length (should be $length)")

  val checkSum  = data.getLong(12)
  if (!checkCheckSum) throw new RuntimeException(f"invalid CRC32 checksum (should be $checkSum%x)")

  val date   = new DateTime(data.getLong(20))

  val nStrings: Int = data.getInt(HEADER_LENGTH)
  val strings: Array[String] = new Array[String](nStrings)

  val itemOffset: Int = initializeStrings + 8
  val nItems: Int = data.getInt(itemOffset - 8)
  val itemSize: Int = data.getInt(itemOffset - 4)

  val mapOffset: Int = itemOffset + (nItems * itemSize) + 8
  val mapLength: Int = data.getInt(mapOffset - 8)
  val mapRehash: Int = data.getInt(mapOffset - 4)

  val kdOffset: Int = mapOffset + (mapLength * 4)

  def checkCheckSum: Boolean = {
    val crc32 = new CRC32
    val lastPos = data.position()
    data.position(HEADER_LENGTH)  // skip the header
    crc32.update(data)
    val x = crc32.getValue()
    data.position(lastPos)

    x == checkSum
  }

  def initializeStrings: Int = {
    val a = strings
    var buf = new Array[Byte](256)

    var i = 0
    var pos = HEADER_LENGTH + 4

    while (i < nStrings) {
      val sLen = data.getInt(pos)
      if (sLen > buf.length) buf = new Array[Byte](sLen)
      data.position(pos+4)
      data.get(buf, 0, sLen)
      a(i) = new String(buf, 0, sLen)
      pos += sLen + 5 // skip terminating 0
      i += 1
    }

    pos
  }

  //--- testing & debugging

  def stringIterator: Iterator[String] = strings.iterator

  def printStructure: Unit = {
    println("--- structure:")
    println(s"schema:       '${strings(0)}'")
    println(s"length:       $length bytes")
    println(f"checkSum:     $checkSum%x")
    println(s"date:         $date")
    println(s"nItems:       $nItems")
    println(s"itemSize:     $itemSize")
    println(s"nStrings:     $nStrings")
    println(s"mapLength:    $mapLength")
    println(s"item offset:  $itemOffset")
    println(s"map offset:   $mapOffset")
    println(s"kd offset:    $kdOffset")
  }

  def printStrings: Unit = {
    println("--- string table:")
    for ((s,i) <- strings.zipWithIndex){
      println(f"$i%5d: '$s'")
    }
  }

  def printItems (n: Int = nItems): Unit = {
    var off = itemOffset
    println("--- items:")
    for (i <- 0 until n){
      val e = readItem(off)
      println(f"$i%5d: $e")
      off += itemSize
    }
    if (n < nItems) println(s"... ($nItems)")
  }
  def printItems (nMaxOpt: Option[Int]): Unit = {
    nMaxOpt match {
      case Some(n) => printItems(n)
      case None => printItems(nItems)
    }
  }

  def foreachItem (f: (T)=>Unit): Unit = {
    var off = itemOffset
    for (i <- 0 until nItems){
      f( readItem(off))
      off += itemSize
    }
  }

  def itemIterator: Iterator[T] = {
    (itemOffset to (itemOffset + nItems * itemSize) by itemSize).map(readItem).iterator
  }

  //--- queries

  def size: Int = nItems

  def isEmpty: Boolean = nItems == 0

  /**
    * to be provided by concrete class - turn raw data into object
    * iIdx is guaranteed to be within 0..nItems
    */
  protected def readItem (iOff: Int): T

  @inline protected def itemId (iOff: Int): String = strings(data.getInt(iOff + 52))

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
        val k = strings(buf.getInt(eOff + 52))
        if (k.equals(key)) return Some(readItem(eOff))
      }

      // hash collision
      idx = nextIndex(idx, h, mapLength, mapRehash)
      i += 1
    }

    throw new RuntimeException(s"entry $key not found in $i iterations - possible map corruption")
  }


  //--- kdtree

  //--- supported geospatial queries

  /**
    * abstract type for kd-tree query results
    */
  abstract class GisItemDBQuery (val pos: GeoPosition) {

    private[GisItemDB]  var x: Double = 0
    private[GisItemDB]  var y: Double = 0
    private[GisItemDB]  var z: Double = 0

    Datum.withECEF(pos)(setXyz)

    private[GisItemDB] def setXyz(lx: Length, ly: Length, lz: Length): Unit = {
      x = lx.toMeters
      y = ly.toMeters
      z = lz.toMeters
    }

    @inline final def _closest (v: Double, min: Double, max: Double): Double = {
      if (v <= min) min
      else if (v >= max) max
      else v
    }

    private[GisItemDB]  def prune(minX: Double, minY: Double, minZ: Double,
                                  maxX: Double, maxY: Double, maxZ: Double): Boolean = {
      val cx = _closest(x, minX, maxX)
      val cy = _closest(y, minY, maxY)
      val cz = _closest(z, minZ, maxZ)
      val d = computeDist(cx, cy, cz)
      !canContain(d)
    }

    private[GisItemDB]  def clear: Unit
    private[GisItemDB]  def canContain(d: Double): Boolean
    private[GisItemDB]  def update(d: Double, iOff: Int): Unit

    // we only need a order-preserving value (save the sqrt)
    private[GisItemDB]  def computeDist(x2: Double, y2: Double, z2: Double): Double = {
      val d2 = squared(x - x2) + squared(y - y2) + squared(z - z2)
      if (d2 > 1e10) { // if distance > 100,000m we approximate with sphere
        // dist = R * crd(a)  => dist = R * 2*sin(a/2) => rad(a) = 2R * asin(dist/2R)
        squared( ME * Math.acos( 1.0 - d2/(2 * ME2)))
      } else d2 // error is less than 1m
    }
  }

  /**
    * find the nearest item for a given position
    */
  class NearestNeighborQuery (pos: GeoPosition) extends GisItemDBQuery(pos) {
    private[GisItemDB]  var itemOff: Int = -1  // offset of nearest item
    private[GisItemDB]  var dist: Double = Double.MaxValue  // distance in Meters of nearest item

    private[GisItemDB]  def clear: Unit = {
      itemOff = -1
      dist = Double.MaxValue
    }

    private[GisItemDB]  def canContain(d: Double): Boolean = d < dist

    private[GisItemDB]  def update(d: Double, iOff: Int): Unit = {
      if (d < dist) {
        dist = d
        itemOff = iOff
      }
    }

    def getItemId: String = if (itemOff == -1) "?" else itemId(itemOff)
    def getItem: Option[T] = if (itemOff == -1) None else Some(readItem(itemOff))
    def getDistance: Length = Meters(Math.sqrt(dist))
    def getResult: Option[(T,Length)] = {
      if (itemOff != -1) Some( (readItem(itemOff),getDistance)) else None
    }

    def foreachItemId (f: (String,Length)=>Unit): Unit = {
      if (itemOff != -1) f( itemId(itemOff),Meters(Math.sqrt(dist)))
    }
    def foreach (f: (T,Length)=>Unit): Unit = {
      if (itemOff != -1) f( readItem(itemOff),Meters(Math.sqrt(dist)))
    }
  }

  def createNearestNeighborQuery (pos: GeoPosition) = new NearestNeighborQuery(pos)

  /**
    * nearest neighbor search
    */
  def withNearestItem (pos: GeoPosition)(f: (T,Length)=>Unit) = {
    if (!isEmpty) {
      val query = new NearestNeighborQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreach(f)
    }
  }

  def getNearestItem (pos: GeoPosition): Option[(T,Length)] = {
    if (!isEmpty) {
      val query = new NearestNeighborQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.getResult
    } else None
  }

  /**
    * find the N nearest neighbors
    * note - we could have used WeightedArray for keeping the match list, but this would require per-query
    * allocation and hence not execute queries in const space
    */
  class NnearestNeighborsQuery(pos: GeoPosition, val maxNeighbors: Int) extends GisItemDBQuery(pos) {
    private[GisItemDB]  val itemOffs: Array[Int] = new Array[Int](maxNeighbors)
    private[GisItemDB]  val dists: Array[Double] = new Array[Double](maxNeighbors)
    private[GisItemDB]  var maxDist: Double = Double.MinValue
    private[GisItemDB]  var nNeighbors: Int = 0

    def size: Int = nNeighbors
    def isEmpty: Boolean = nNeighbors == 0

    private[GisItemDB]  def clear: Unit = {
      maxDist = Double.MinValue
      nNeighbors = 0
    }

    private[GisItemDB] def canContain(d: Double): Boolean = {
      nNeighbors < maxNeighbors || d < maxDist
    }

    private[GisItemDB]  def update(d: Double, iOff: Int): Unit = {

      def insertPosition: Int = {
        var i = 0
        while (i < nNeighbors) {
          if (dists(i) > d) return i
          i += 1
        }
        -1
      }

      if (nNeighbors < maxNeighbors) { // not yet full
        if (d >= maxDist) {  // also used for first entry
          dists(nNeighbors) = d
          itemOffs(nNeighbors) = iOff
          nNeighbors += 1
          maxDist = d

        } else {
          val i = insertPosition
          System.arraycopy(dists, i, dists, i+1, nNeighbors - i)
          System.arraycopy(itemOffs, i, itemOffs, i+1, nNeighbors - i)
          dists(i) = d
          itemOffs(i) = iOff
          nNeighbors += 1
        }

      } else { // full
        if (d < maxDist) {
          val i = insertPosition // can't be the last position (this would otherwise be maxDist)
          val nn1 = nNeighbors-1
          System.arraycopy(dists, i, dists, i+1, nn1 - i)
          System.arraycopy(itemOffs, i, itemOffs, i+1, nn1 - i)
          dists(i) = d
          itemOffs(i) = iOff
          maxDist = dists(nn1)
        } // otherwise ignore
      }
    }

    def getItemIds: Array[String] = itemOffs.map(itemId)
    def getItems: Seq[T] = itemOffs.map(readItem)

    def getMaxDistance: Length = {
      if (nNeighbors == 0) Length.UndefinedLength else Meters(Math.sqrt(maxDist))
    }
    def getDistances: Array[Length] = dists.map(d=> Meters(Math.sqrt(d)))

    def foreachItemId (f: (String,Length)=>Unit): Unit = {
      var i = 0
      while (i < nNeighbors) {
        f( itemId(itemOffs(i)), Meters(dists(i)))
        i += 1
      }
    }

    def foreach (f: (T,Length)=>Unit): Unit = {
      var i = 0
      while (i < nNeighbors) {
        f( readItem(itemOffs(i)), Meters(dists(i)))
        i += 1
      }
    }

    def getResult: Seq[(T,Length)] = getItems.zip(getDistances)
  }

  def createNnearestNeighborsQuery (pos: GeoPosition, nItems: Int): NnearestNeighborsQuery = {
    new NnearestNeighborsQuery( pos, nItems)
  }

  def foreachNearItem (pos: GeoPosition, nItems: Int)(f: (T,Length)=>Unit) = {
    if (!isEmpty) {
      val query = new NnearestNeighborsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreach(f)
    }
  }

  def getNnearestItems (pos: GeoPosition, nItems: Int): Seq[(T,Length)] = {
    if (!isEmpty) {
      val query = new NnearestNeighborsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.getResult
    } else Seq.empty[(T,Length)]
  }

  /**
    * find all items within given range
    */
  class RangeNeighborsQuery(pos: GeoPosition, val maxDistance: Length) extends GisItemDBQuery(pos) {
    private[GisItemDB]  val itemOffs = new ArrayBuffer[Int]
    private[GisItemDB]  val dists = new ArrayBuffer[Double]
    private[GisItemDB]  val maxDist2 = squared(maxDistance.toMeters)

    def size = itemOffs.size
    def isEmpty = itemOffs.isEmpty

    private[GisItemDB]  def clear: Unit = {
      itemOffs.clear
      dists.clear
    }

    private[GisItemDB]  def canContain(d: Double): Boolean = d <= maxDist2

    /**
      * while not strictly required we sort new items in so that we don't have to
      * sort two separate arrays when returning/processing results
      */
    private[GisItemDB]  def update(d: Double, iOff: Int): Unit = {
      if (d <= maxDist2) {
        if (dists.isEmpty || d >= dists.last){
          dists += d
          itemOffs += iOff
        } else {
          val n1 = dists.size - 1
          var i=0
          while (i < n1 && d >= dists(i)) i += 1
          //--- move last item
          dists += dists.last
          itemOffs += itemOffs.last
          //--- shift items up
          var j = n1
          while (j > i) {
            val j1 = j - 1
            dists.update(j, dists(j1))
            itemOffs.update(j, itemOffs(j1))
            j = j1
          }
          //--- insert new item
          dists.update(i, d)
          itemOffs.update(i, iOff)
        }
      }
    }

    def getItemIds: Seq[String] = itemOffs.map(itemId)
    def getItems: Seq[T] = itemOffs.map(readItem)

    def getDistances: Seq[Length] = dists.map(d=> Meters(Math.sqrt(d)))

    def foreachItemId (f: (String,Length)=>Unit): Unit = {
      val nNeighbors = size
      var i = 0
      while (i < nNeighbors) {
        f( itemId(itemOffs(i)), Meters(dists(i)))
        i += 1
      }
    }

    def foreach (f: (T,Length)=>Unit): Unit = {
      val nNeighbors = size
      var i = 0
      while (i < nNeighbors) {
        f( readItem(itemOffs(i)), Meters(dists(i)))
        i += 1
      }
    }

    def getResult: Seq[(T,Length)] = getItems.zip(getDistances)
  }

  def createRangeNeighborsQuery (pos: GeoPosition, radius: Length): RangeNeighborsQuery = {
    new RangeNeighborsQuery(pos, radius)
  }

  def foreachRangeItem (pos: GeoPosition, radius: Length)(f: (T,Length)=>Unit) = {
    if (!isEmpty) {
      val query = new RangeNeighborsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreach(f)
    }
  }

  def getRangeItems (pos: GeoPosition, radius: Length): Seq[(T,Length)] = {
    if (!isEmpty) {
      val query = new RangeNeighborsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.getResult
    } else Seq.empty[(T,Length)]
  }

  //var nSteps: Int = 0

  /**
    * use this for const space queries that are cached by the client
    */
  @inline def processQuery (query: GisItemDBQuery): Unit = {
    query.clear
    searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
  }

  protected final def searchKdTree(query: GisItemDBQuery, nodeOff: Int, depth: Int,
                                   minX: Double, minY: Double, minZ: Double, // the bounding hyperrect for this node
                                   maxX: Double, maxY: Double, maxZ: Double): Unit = {
    //nSteps += 1
    val itemOff = data.getInt(nodeOff)
    val curX = data.getDouble(itemOff + 4)
    val curY = data.getDouble(itemOff + 12)
    val curZ = data.getDouble(itemOff + 20)

    val leftChild = data.getInt(nodeOff + 4)  // node offset of left child
    val rightChild = data.getInt(nodeOff + 8)  // node offset of right child

    //--- update result for current node
    val d = query.computeDist(curX, curY, curZ)
    query.update(d, itemOff)

    //--- split hyperrect
    var nearOff = EMPTY
    var nearMinX = minX;  var nearMinY = minY;  var nearMinZ = minZ
    var nearMaxX = maxX;  var nearMaxY = maxY;  var nearMaxZ = maxZ

    var farOff = EMPTY
    var farMinX = minX;  var farMinY = minY;  var farMinZ = minZ
    var farMaxX = maxX;  var farMaxY = maxY;  var farMaxZ = maxZ

    depth % 3 match {
      case 0 => { // x split
        if (query.x <= curX) {
          nearOff = leftChild;   nearMaxX = curX
          farOff  = rightChild;  farMinX  = curX
        } else {
          nearOff = rightChild;  nearMinX = curX
          farOff  = leftChild;   farMaxX  = curX
        }
      }
      case 1 => { // y split
        if (query.y <= curY) {
          nearOff = leftChild;   nearMaxY = curY
          farOff  = rightChild;  farMinY  = curY
        } else {
          nearOff = rightChild;  nearMinY = curY
          farOff  = leftChild;   farMaxY  = curY
        }
      }
      case 2 => { // z split
        if (query.z <= curZ) {
          nearOff = leftChild;   nearMaxZ = curZ
          farOff  = rightChild;  farMinZ  = curZ
        } else {
          nearOff = rightChild;  nearMinZ = curZ
          farOff  = leftChild;   farMaxZ  = curZ
        }
      }
    }

    if (nearOff != EMPTY) {
      searchKdTree(query, nearOff, depth+1, nearMinX,nearMinY,nearMinZ,nearMaxX,nearMaxY,nearMaxZ)
    }

    if (farOff != EMPTY) {
      // descend into far subtree only if the corresponding hyperrect /could/ have a matching point
      // (this is where the kd-tree gets its O(log(N)) from)
      if (!query.prune(farMinX,farMinY,farMinZ,farMaxX,farMaxY,farMaxZ)) {
        searchKdTree(query, farOff, depth+1, farMinX,farMinY,farMinZ,farMaxX,farMaxY,farMaxZ)
      }
    }
  }
}
