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

import java.io.{DataOutputStream, File, FileOutputStream, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Arrays

import gov.nasa.race.geo.XyzPos
import gov.nasa.race.util.{ArrayUtils, FileUtils}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


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
abstract class GisItemDBFactory[T <: GisItem] {

  val strMap = new mutable.LinkedHashMap[String,Int]
  val items  = new ArrayBuffer[T]
  val xyzPos = new ArrayBuffer[XyzPos]

  var itemOffset = 0  // byte offset if item0

  //--- to be provided by concrete class
  val schema: String
  val itemSize: Int // in bytes

  // to be provided by concrete factory
  protected def writeItem (it: T, dos: DataOutputStream): Unit


  def createDB (outFile: File): Boolean = {
    println(s"error - no source to create $outFile")
    false
  }

  def createDB (outFile: File, inFile: File): Boolean = {
    if (FileUtils.existingNonEmptyFile(inFile).isDefined){
      if (FileUtils.ensureWritable(outFile).isDefined){
        if (parse(inFile)) {
          write(outFile)
          true
        } else {
          println(s"error - no items found in: $inFile")
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

  /**
    * override if this factory can parse input sources such as SQL, CSV etc
    */
  protected def parse (inFile: File): Boolean = false

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

    writeHeader(dos)
    writeStrMap(dos)
    writeItems(dos)
    writeKeyMap(dos)
    writeKdTree(dos)

    dos.close
  }

  protected def writeHeader (dos: DataOutputStream): Unit = {
    dos.writeInt(GisItemDB.MAGIC)
    dos.writeInt(0)   // file length (filled in later)
    dos.writeInt(0)   // CRC32 checksum (filled in later)
    dos.writeLong(0)  // version date of data
  }

  protected def writeStrMap (dos: DataOutputStream): Unit = {
    dos.writeInt(strMap.size)  // nStrings
    for (e <- strMap) {
      val s = e._1
      val b = s.getBytes       // without terminating 0
      dos.writeInt(b.length)
      dos.write(b)
      dos.write(0)
    }
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
    import GisItemDB._
    import GisItemDBFactory._

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
      dos.writeInt( itemOff) // offset of node item
      dos.writeInt( nodeOffset(leftNode(i)))
      dos.writeInt( nodeOffset(rightNode(i)))
    }
  }
}
