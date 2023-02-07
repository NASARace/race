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
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import java.util.zip.CRC32

import gov.nasa.race.common._
import gov.nasa.race.geo._
import gov.nasa.race.uom._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime

import scala.Double.{MaxValue => DMax, MinValue => DMin}
import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer
import scala.reflect._

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
  *     i16 strLen             // in bytes, without terminating 0
  *     char strBytes[strLen]  // string bytes (mod-UTF8), with terminating 0
  *   } [nStrings]
  *
  *   u8 pad[]              // to 4byte-align entry list
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
  * data is stored in little endian format, to simplify native client processing
  *
  * total size of structure in bytes:
  *   28 + (4 + nStrings*4 + nChars + nStrings) + (8 + nItems*sizeOfItem) + (8 + mapItems*4) + (nItems * 12)
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

abstract class GisItemDB[T <: GisItem: ClassTag] (data: ByteBuffer) {
  import GisItemDB._

  def this (f: File) = this(GisItemDB.mapFile(f))

  data.order(ByteOrder.LITTLE_ENDIAN)
  if (data.getInt(0) != MAGIC) throw new RuntimeException("invalid file magic (should be 'RGIS')")

  val length = data.getLong(4)
  if (data.limit() != length) throw new RuntimeException(s"invalid data length (should be $length)")

  val checkSum  = data.getLong(12)
  if (!checkCheckSum) throw new RuntimeException(f"invalid CRC32 checksum (should be $checkSum%x)")

  val date   = DateTime.ofEpochMillis(data.getLong(20))

  val nStrings: Int = data.getInt(HEADER_LENGTH)
  if (nStrings <= 1) throw new RuntimeException("no schema found in DB")
  val strings: Array[String] = loadStrings(nStrings)
  if (strings(0) != schema) throw new RuntimeException(s"wrong schema '${strings(0)}' found in DB (should be $schema)")

  skipPadding

  val itemOffset: Int = position + 8  // beginning of item data
  val nItems: Int = data.getInt(itemOffset - 8)
  val itemSize: Int = data.getInt(itemOffset - 4)

  val mapOffset: Int = itemOffset + (nItems * itemSize) + 8  // beginning of key map
  val mapLength: Int = data.getInt(mapOffset - 8)
  val mapRehash: Int = data.getInt(mapOffset - 4)

  val kdOffset: Int = mapOffset + (mapLength * 4)  // beginning of kdtree nodes

  //--- end initialization

  @inline final def position: Int = data.position()
  @inline final def setPosition(newPos: Int) = data.position(newPos)


  def checkCheckSum: Boolean = {
    val crc32 = new CRC32
    val lastPos = position
    setPosition(HEADER_LENGTH)  // skip the header
    crc32.update(data)
    val x = crc32.getValue()
    setPosition(lastPos)

    x == checkSum
  }

  def loadStrings(n: Int): Array[String] = {
    val a = new Array[String](n)
    var buf = new Array[Byte](256)

    var i = 0
    setPosition(HEADER_LENGTH + 4)

    while (i < nStrings) {
      val sLen = data.getShort
      if (sLen > buf.length) buf = new Array[Byte](sLen)
      data.get(buf, 0, sLen)
      a(i) = new String(buf, 0, sLen, StandardCharsets.UTF_8)
      data.get() // skip terminating 0
      i += 1
    }

    a
  }

  def skipPadding: Int = {
    var pos = position
    val x = pos % 4
    if (x > 0) {
      pos += 4 - x
      setPosition(pos)
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

  /**
    * what schema do we implement - override if this does not correspond with class name of item type
    */
  def schema: String = classTag[T].runtimeClass.getName

  // offset based accessors for common GisItem fields
  @inline final protected def itemId(iOff: Int): String = strings(data.getInt(iOff + 52))
  @inline final protected def itemLat(iOff: Int): Double = data.getDouble(iOff + 28)
  @inline final protected def itemLon(iOff: Int): Double = data.getDouble(iOff + 36)
  @inline final protected def itemAlt(iOff: Int): Double = data.getDouble(iOff + 44)

  @inline final protected def itemPos(iOff: Int): GeoPosition = {
    val lat = itemLat(iOff)
    val lon = itemLon(iOff)
    val alt = itemAlt(iOff)
    GeoPosition.fromDegreesAndMeters(lat,lon,alt)
  }

  //--- O(N) queries - those should not be the primary use case

  def foreach (f: (T)=>Unit): Unit = {
    var iOff = itemOffset
    var i = 0
    while (i < nItems) {
      f(readItem(iOff))
      iOff += itemSize
      i += 1
    }
  }

  def foreachItemId (f: (String)=>Unit) = {
    var iOff = itemOffset
    var i = 0
    while (i < nItems) {
      f(itemId(iOff))
      iOff += itemSize
      i += 1
    }
  }

  def foreachItemIdPos (f: (String,GeoPosition)=>Unit) = {
    var iOff = itemOffset
    var i = 0
    while (i < nItems) {
      f(itemId(iOff),itemPos(iOff))
      iOff += itemSize
      i += 1
    }
  }

  def find (f: (T)=>Boolean): Option[T] = {
    var iOff = itemOffset
    var i = 0
    while (i < nItems) {
      val t = readItem(iOff)
      if (f(t)) return Some(t)
      iOff += itemSize
      i += 1
    }
    None
  }

  def find (f: (String,Angle,Angle,Length)=>Boolean): Option[T] = {
    var iOff = itemOffset
    var i = 0
    while (i < nItems) {
      val id = itemId(iOff)
      val lat = itemLat(iOff)
      val lon = itemLon(iOff)
      val alt = itemAlt(iOff)
      if (f(id,Degrees(lat),Degrees(lon),Meters(alt))) return Some(readItem(iOff))
      iOff += itemSize
      i += 1
    }
    None
  }


  //--- key map - this is O(1) on average

  /**
    * alphanumeric key lookup
    */
  protected def getItemOff (key: String): Int = {
    val buf = data
    val h = key.hashCode
    var idx = (intToUnsignedLong(h) % mapLength).toInt

    var i = 0
    while (i < nItems) {
      val eOff = buf.getInt(mapOffset + (idx * 4))
      if (eOff == EMPTY) return -1

      if (buf.getInt(eOff) == h) {
        val k = strings(buf.getInt(eOff + 52))
        if (k.equals(key)) return eOff
      }

      // hash collision
      idx = nextIndex(idx, h, mapLength, mapRehash)
      i += 1
    }

    throw new RuntimeException(s"entry $key not found in $i iterations - possible map corruption")
  }

  def getItem (key: String): Option[T] = {
    getItemOff(key) match {
      case -1 => None
      case eOff => Some(readItem(eOff))
    }
  }

  def withItemPos (key: String)(f: (Angle,Angle,Length)=> Unit): Unit = {
    val eOff = getItemOff(key)
    if (eOff != -1) {
      val lat = itemLat(eOff)
      val lon = itemLon(eOff)
      val alt = itemAlt(eOff)
      f( Degrees(lat), Degrees(lon), Meters(alt))
    }
  }

  def withItemPos (key: String)(f: (GeoPosition)=> Unit): Unit = {
    val eOff = getItemOff(key)
    if (eOff != -1) f( itemPos(eOff))
  }

  //--- kdtree - O(log N) on average

  //--- supported geospatial queries

  /**
    * abstract type for kd-tree query results
    */
  abstract class GisItemDBQuery (protected var pos: GeoPosition) {

    private[GisItemDB]  var x: Double = 0
    private[GisItemDB]  var y: Double = 0
    private[GisItemDB]  var z: Double = 0

    Datum.withECEF(pos.lat.toRadians, pos.lon.toRadians, pos.altMeters)(setXyz)

    /** NOTE - don't change while processQuery on same query object is running */
    def setPos (newPos: GeoPosition): Unit = {
      pos = newPos
      Datum.withECEF(pos.lat.toRadians, pos.lon.toRadians, pos.altMeters)(setXyz)
    }
    def getPos: GeoPosition = pos

    private[GisItemDB] def setXyz(xMeters: Double, yMeters: Double, zMeters: Double): Unit = {
      x = xMeters
      y = yMeters
      z = zMeters
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

    // we only need an order-preserving value (save the sqrt)
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
  class NearestItemQuery (pos: GeoPosition) extends GisItemDBQuery(pos) {
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

    @inline final def getItemId: String = if (itemOff == -1) "?" else itemId(itemOff)
    def getItem: Option[T] = if (itemOff == -1) None else Some(readItem(itemOff))
    @inline final def getDistance: Length = Meters(Math.sqrt(dist))

    def getResult: Option[(Length,T)] = {
      if (itemOff != -1) Some( (getDistance,readItem(itemOff))) else None
    }

    def withItemId(f: (Length,String)=>Unit): Unit = {
      if (itemOff != -1) f( getDistance, itemId(itemOff))
    }
    def withItemIdPos(f: (Length,String,Angle,Angle,Length)=>Unit) = {
      if (itemOff != -1){
        val id = itemId(itemOff)
        val lat = itemLat(itemOff)
        val lon = itemLon(itemOff)
        val alt = itemAlt(itemOff)
        f( getDistance, id, Degrees(lat), Degrees(lon), Meters(alt))
      }
    }
    def withItemIdPos(f: (Length,String,GeoPosition)=>Unit) = {
      if (itemOff != -1){
        val id = itemId(itemOff)
        val pos = itemPos(itemOff)
        f( getDistance, id, pos)
      }
    }
    def withItem(f: (Length,T)=>Unit): Unit = {
      if (itemOff != -1) f( getDistance, readItem(itemOff))
    }
  }

  def createNearestItemQuery(pos: GeoPosition) = new NearestItemQuery(pos)

  /**
    * nearest neighbor search
    */
  def withNearestItem (pos: GeoPosition)(f: (Length,T)=>Unit) = {
    if (!isEmpty) {
      val query = new NearestItemQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.withItem(f)
    }
  }

  def withNearestItemId (pos: GeoPosition)(f: (Length,String)=>Unit) = {
    if (!isEmpty) {
      val query = new NearestItemQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.withItemId(f)
    }
  }
  def withNearestItemIdPos (pos: GeoPosition)(f: (Length,String,Angle,Angle,Length)=>Unit) = {
    if (!isEmpty) {
      val query = new NearestItemQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.withItemIdPos(f)
    }
  }
  def withNearestItemIdPos (pos: GeoPosition)(f: (Length,String,GeoPosition)=>Unit) = {
    if (!isEmpty) {
      val query = new NearestItemQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.withItemIdPos(f)
    }
  }

  def getNearestItem (pos: GeoPosition): Option[(Length,T)] = {
    if (!isEmpty) {
      val query = new NearestItemQuery(pos)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.getResult
    } else None
  }

  /**
    * find the N nearest neighbors
    * note - we could have used WeightedArray for keeping the match list, but this would require per-query
    * allocation and hence not execute queries in const space
    */
  class NearItemsQuery (p: GeoPosition, protected var maxItems: Int) extends GisItemDBQuery(p) {
    private[GisItemDB]  val itemOffs: Array[Int] = new Array[Int](maxItems)
    private[GisItemDB]  val dists: Array[Double] = new Array[Double](maxItems)
    private[GisItemDB]  var maxDist: Double = Double.MinValue
    private[GisItemDB]  var nNeighbors: Int = 0

    def size: Int = nNeighbors
    def isEmpty: Boolean = nNeighbors == 0

    /** NOTE - don't change while processQuery on same query object is running */
    def setMaxItems (newMaxItems: Int): Unit = { maxItems = newMaxItems }
    def getMaxItems: Int = maxItems

    private[GisItemDB]  def clear: Unit = {
      maxDist = Double.MinValue
      nNeighbors = 0
    }

    private[GisItemDB] def canContain(d: Double): Boolean = {
      nNeighbors < maxItems || d < maxDist
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

      if (nNeighbors < maxItems) { // not yet full
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

    @inline final def getItemId(i: Int): String = itemId(itemOffs(i))
    @inline final def getDistance(i: Int): Length = Meters(Math.sqrt(dists(i)))

    def foreachItemId (f: (Length,String)=>Unit): Unit = {
      var i = 0
      while (i < nNeighbors) {
        f( getDistance(i), getItemId(i))
        i += 1
      }
    }
    def foreachItemIdPos (f: (Length,String,Angle,Angle,Length)=>Unit): Unit = {
      var i = 0
      while (i < nNeighbors) {
        val iOff = itemOffs(i)
        val lat = itemLat(iOff)
        val lon = itemLon(iOff)
        val alt = itemAlt(iOff)

        f( getDistance(i), getItemId(i), Degrees(lat), Degrees(lon), Meters(alt))
        i += 1
      }
    }

    def foreachItemIdPos (f: (Length,String,GeoPosition)=>Unit): Unit = {
      var i = 0
      while (i < nNeighbors) {
        f( getDistance(i), getItemId(i), itemPos(itemOffs(i)))
        i += 1
      }
    }

    def foreach (f: (Length,T)=>Unit): Unit = {
      var i = 0
      while (i < nNeighbors) {
        f( getDistance(i), readItem(itemOffs(i)))
        i += 1
      }
    }

    def getResult: Seq[(Length,T)] = getDistances.zip(getItems)
  }

  def createNearItemsQuery(pos: GeoPosition, nItems: Int): NearItemsQuery = {
    new NearItemsQuery( pos, nItems)
  }

  def foreachNearItem (pos: GeoPosition, nItems: Int)(f: (Length,T)=>Unit) = {
    if (!isEmpty) {
      val query = new NearItemsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreach(f)
    }
  }

  def foreachNearItemId (pos: GeoPosition, nItems: Int)(f: (Length,String)=>Unit) = {
    if (!isEmpty) {
      val query = new NearItemsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreachItemId(f)
    }
  }
  def foreachNearItemIdPos (pos: GeoPosition, nItems: Int)(f: (Length,String,Angle,Angle,Length)=>Unit) = {
    if (!isEmpty) {
      val query = new NearItemsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreachItemIdPos(f)
    }
  }
  def foreachNearItemIdPos (pos: GeoPosition, nItems: Int)(f: (Length,String,GeoPosition)=>Unit) = {
    if (!isEmpty) {
      val query = new NearItemsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreachItemIdPos(f)
    }
  }

  def getNearItems (pos: GeoPosition, nItems: Int): Seq[(Length,T)] = {
    if (!isEmpty) {
      val query = new NearItemsQuery(pos,nItems)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.getResult
    } else Seq.empty[(Length,T)]
  }

  /**
    * find all items within given range
    */
  class RangeItemsQuery (pos: GeoPosition, protected var maxDistance: Length) extends GisItemDBQuery(pos) {
    private[GisItemDB]  val itemOffs = new ArrayBuffer[Int]
    private[GisItemDB]  val dists = new ArrayBuffer[Double]
    private[GisItemDB]  val maxDist2 = squared(maxDistance.toMeters)

    def size = itemOffs.size
    def isEmpty = itemOffs.isEmpty

    /** NOTE - don't change while processQuery on same query object is running */
    def setMaxDistance (newMaxDistance: Length): Unit = { maxDistance = newMaxDistance }
    def getMaxDistance: Length = maxDistance

    private[GisItemDB]  def clear: Unit = {
      itemOffs.clear()
      dists.clear()
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

    @inline final def getItemId(i: Int): String = itemId(itemOffs(i))
    @inline final def getDistance(i: Int): Length = Meters(Math.sqrt(dists(i)))

    def foreachItemId (f: (Length,String)=>Unit): Unit = {
      val nNeighbors = size
      var i = 0
      while (i < nNeighbors) {
        f( getDistance(i),getItemId(i))
        i += 1
      }
    }
    def foreachItemIdPos (f: (Length,String,Angle,Angle,Length)=>Unit): Unit = {
      val nNeighbors = size
      var i = 0
      while (i < nNeighbors) {
        val iOff = itemOffs(i)
        val lat = itemLat(iOff)
        val lon = itemLon(iOff)
        val alt = itemAlt(iOff)
        f( getDistance(i), itemId(iOff), Degrees(lat),Degrees(lon),Meters(alt))
        i += 1
      }
    }
    def foreachItemIdPos (f: (Length,String,GeoPosition)=>Unit): Unit = {
      val nNeighbors = size
      var i = 0
      while (i < nNeighbors) {
        val iOff = itemOffs(i)
        f( getDistance(i), itemId(iOff), itemPos(iOff))
        i += 1
      }
    }

    def foreach (f: (Length,T)=>Unit): Unit = {
      val nNeighbors = size
      var i = 0
      while (i < nNeighbors) {
        f( getDistance(i), readItem(itemOffs(i)))
        i += 1
      }
    }

    def getResult: Seq[(Length,T)] = getDistances.zip(getItems)
  }

  def createRangeItemsQuery(pos: GeoPosition, radius: Length): RangeItemsQuery = {
    new RangeItemsQuery(pos, radius)
  }

  def foreachRangeItem (pos: GeoPosition, radius: Length)(f: (Length,T)=>Unit) = {
    if (!isEmpty) {
      val query = new RangeItemsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreach(f)
    }
  }

  def foreachRangeItemId (pos: GeoPosition, radius: Length)(f: (Length,String)=> Unit): Unit = {
    if (!isEmpty) {
      val query = new RangeItemsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreachItemId(f)
    }
  }
  def foreachRangeItemIdPos (pos: GeoPosition, radius: Length)(f: (Length,String,Angle,Angle,Length)=> Unit): Unit = {
    if (!isEmpty) {
      val query = new RangeItemsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreachItemIdPos(f)
    }
  }
  def foreachRangeItemIdPos (pos: GeoPosition, radius: Length)(f: (Length,String,GeoPosition)=> Unit): Unit = {
    if (!isEmpty) {
      val query = new RangeItemsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.foreachItemIdPos(f)
    }
  }

  def getRangeItems (pos: GeoPosition, radius: Length): Seq[(Length,T)] = {
    if (!isEmpty) {
      val query = new RangeItemsQuery(pos,radius)
      searchKdTree(query, kdOffset, 0, DMin,DMin,DMin, DMax,DMax,DMax)
      query.getResult
    } else Seq.empty[(Length,T)]
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
