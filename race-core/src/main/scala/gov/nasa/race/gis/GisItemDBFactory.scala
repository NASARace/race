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

import java.io.{DataOutput, File, FileOutputStream, RandomAccessFile}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.zip.CRC32

import gov.nasa.race._
import gov.nasa.race.geo.XyzPos
import gov.nasa.race.util.{ArrayUtils, FileUtils, LeDataOutputStream}
import gov.nasa.race.uom.DateTime

import scala.collection.Seq
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.classTag
import scala.reflect._


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
  * factory objects to create / load concrete GisItemDBs
  *
  * Note that we use a regular class here (instead of a companion object) to simplify
  * instantiation via reflection
  */
abstract class GisItemDBFactory[T <: GisItem: ClassTag] (val itemSize: Int) {
  import GisItemDBFactory._

  val strMap = new mutable.LinkedHashMap[String,Int]
  val items  = new ArrayBuffer[T]
  val xyzPos = new ArrayBuffer[XyzPos]

  var itemOffset = 0  // byte offset if item0

  // to be provided by concrete factory - this has to write all fields other than hash, position(s) and name
  protected def writeItemPayloadFields(it: T, buf: ByteBuffer): Unit

  /**
    * override if schema does not correspond with concrete item type
    */
  def schema: String = classTag[T].runtimeClass.getName

  def createDB (outFile: File, extraArgs: Seq[String], date: DateTime): Boolean = {
    println(s"error - no source to create $outFile")
    false
  }

  def createDB (outFile: File, inFile: File, extraArgs: Seq[String], date: DateTime): Boolean = {
    if (FileUtils.existingNonEmptyFile(inFile).isDefined){
      if (FileUtils.ensureWritable(outFile).isDefined){
        reset
        println(s"parsing input file $inFile")
        if (parse(inFile, extraArgs)) {
          write(outFile, date)
          true
        } else {
          println(s"error - no items found in: $inFile")
          false
        }
      } else {
        println(s"invalid output file: $outFile")
        false
      }
    } else {
      println(s"invalid input file: $inFile")
      false
    }
  }

  /**
    * override if this factory can parse input sources such as SQL, CSV etc
    */
  protected def parse (inFile: File, extraArgs: Seq[String]): Boolean = false

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

  protected def reset: Unit = {
    strMap.clear()
    items.clear()

    addString(schema)
  }

  def addString (s: String): Boolean = {
    val n = strMap.size
    strMap.getOrElseUpdate(s, n)
    strMap.size > n
  }

  protected def addItem (e: T): Unit = {
    e.addStrings(this)

    items += e
    xyzPos += e.ecef
  }


  /**
    * get byte length of UTF-8 encoded string data segment
    * byte length is stored as Short, \0 is appended as C-string termination in case of ASCII strings
    */
  protected def stringDataLength: Int = {
    strMap.foldLeft(0)( (acc,e) => acc + e._1.getBytes(StandardCharsets.UTF_8).length + 3)
  }

  protected def calculateLength: Int = {
    val slen = stringDataLength
    val pad = slen % 4
    val padding = if (pad > 0) 4 - pad else 0

    (GisItemDB.HEADER_LENGTH +
      4 + slen +               // string segment
      padding +
      8 + items.size * itemSize +          // item segment
      8 + getMapConst(items.size)._2 * 4 + // keymap segment
      items.size * 12)                     // kd-tree segment
  }

  def write (outFile: File, date: DateTime): Unit = {
    val len = calculateLength
    println(s"writing output file $outFile ($len bytes)..")

    val fileChannel = new RandomAccessFile(outFile, "rw").getChannel
    fileChannel.truncate(len)
    val buf = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, len)
    buf.order(ByteOrder.LITTLE_ENDIAN)

    writeHeader(buf,len,date)
    writeStrMap(buf)
    writeItems(buf)
    writeKeyMap(buf)
    writeKdTree(buf)

    fillInHeaderValues(buf)
    buf.force()
    println("done.")
  }

  protected def writeHeader (buf: ByteBuffer, len: Int, date: DateTime): Unit = {
    println("writing header")
    buf.putInt(GisItemDB.MAGIC)
    // place holders to be filled in later
    buf.putLong(len)  // data/file length in bytes
    buf.putLong(0)  // CRC32 checksum
    buf.putLong(date.toEpochMillis)  // version date of data
  }

  // not very efficient but this is executed once
  def fillInHeaderValues(buf: ByteBuffer): Unit = {
    println("computing checksum")

    val crc32 = new CRC32
    buf.position(GisItemDB.HEADER_LENGTH)
    crc32.update(buf)

    buf.putLong(12, crc32.getValue)
  }


  protected def writeStrMap (buf: ByteBuffer): Unit = {
    println("writing string map")
    buf.putInt(strMap.size)  // nStrings
    for (e <- strMap) {
      val s = e._1
      val b = s.getBytes(StandardCharsets.UTF_8)       // without terminating 0
      buf.putShort(b.length.toShort)
      buf.put(b)
      buf.put(0.toByte)
    }

    val pad: Int = buf.position() % 4
    if (pad > 0) {
      repeat(4 - pad)(buf.put(0.toByte))
    }
  }

  protected def writeItems (buf: ByteBuffer): Unit = {
    println("writing items")
    buf.putInt(items.size)
    buf.putInt(itemSize)

    itemOffset = buf.position
    items.foreach { it => writeItem(it, buf) }
  }

  protected def writeItem(e: T, buf: ByteBuffer): Unit = {
    writeItemHeaderFields(e,buf)
    writeItemPayloadFields(e,buf)
  }

  protected def writeItemHeaderFields(e: T, buf: ByteBuffer): Unit = {
    val nameIdx = strMap(e.name)
    val pos = e.pos
    val ecef = e.ecef

    buf.putInt(e.hash)
    buf.putDouble(ecef.xMeters)
    buf.putDouble(ecef.yMeters)
    buf.putDouble(ecef.zMeters)

    buf.putDouble(pos.latDeg)
    buf.putDouble(pos.lonDeg)
    buf.putDouble(pos.altMeters)

    buf.putInt(nameIdx)
  }

  protected def writeKeyMap (buf: ByteBuffer): Unit = {
    import GisItemDB._
    import GisItemDBFactory._

    println("writing id map")
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

    buf.putInt(mapLength)
    buf.putInt(rehash)
    slots.foreach(buf.putInt)
  }

  protected def writeKdTree (buf: ByteBuffer): Unit = {
    println("writing kd-tree")

    val orderings = Array(
      new Ordering[Int]() { def compare(eIdx1: Int, eIdx2: Int) = xyzPos(eIdx1).x compare xyzPos(eIdx2).x },
      new Ordering[Int]() { def compare(eIdx1: Int, eIdx2: Int) = xyzPos(eIdx1).y compare xyzPos(eIdx2).y },
      new Ordering[Int]() { def compare(eIdx1: Int, eIdx2: Int) = xyzPos(eIdx1).z compare xyzPos(eIdx2).z }
    )

    val nItems = items.size
    val node0Offset: Int = buf.position

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
      if (i0 > i1) {
        -1

      } else if (i0 == i1) {
        val nodeIdx = n
        n += 1
        itemIdx(nodeIdx) = eIdxs(i0)
        leftNode(nodeIdx) = -1
        rightNode(nodeIdx) = -1

        nodeIdx

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
      val itemOff = itemOffset + (itemIdx(i) * itemSize)
      buf.putInt( itemOff) // offset of node item
      buf.putInt( nodeOffset(leftNode(i)))
      buf.putInt( nodeOffset(rightNode(i)))
    }
  }
}
