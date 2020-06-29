/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.common

import java.util.{Arrays => JArrays}

import scala.annotation.tailrec


/**
  * simple Boyer Moore search using Array[Byte] objects
  *
  * this as a light weight alternative to Regex based search
  *
  * Note that we only support patterns consisting of ASCII chars (<256), and only up to
  * a pattern length of 127 so that we don't need to promote jump table values to unsigned
  *
  * TODO - the interface should enforce that we only search in ASCII data
  *
  * TODO - this could be extended to full utf-8 by using a sparse jump table (e.g. LongMap)
  * but this could nix performance gains compared to Regex (especially since we provide
  * MutUTF8CharSequence to avoid data copies)
  */
class BMSearch (val pattern: Array[Byte], val patternOffset: Int, val patternLength: Int) {

  final val MaxPatternLength = 127

  def this (pat: Array[Byte]) = this( pat,0,pat.length)
  def this (pat: String) = this( pat.getBytes)
  def this (pat: ByteSlice) = this( pat.data,pat.off,pat.len)

  assert(patternLength < MaxPatternLength)

  private val shift = new Array[Byte](256) // alphabet size
  initShift(shift) // note this stores bytes, access has to convert to unsigned

  private var sdb: StringDataBuffer = null // initialized on demand if we search in strings

  private def getStringDataBuffer (minLen: Int): StringDataBuffer = {
    if (sdb == null) {
      sdb = new AsciiBuffer( Math.max(minLen,8192))
    } else {
      sdb.clear()
    }
    sdb
  }

  def clear: Unit = {
    // release any transient resources
    if (sdb != null) sdb = null
  }

  protected def initShift(shiftTable: Array[Byte]): Unit  = {
    val m = patternLength
    val m1 = m-1
    JArrays.fill(shiftTable,m.toByte)

    var i = 0
    while (i < m1){
      shiftTable( pattern(patternOffset+i) & 0xff) = (m1 -i).toByte
      i += 1
    }
  }

  //--- all occurrences

  def foreachIndexIn (bs: Array[Byte], off: Int, len: Int)(f: Int=>Unit): Unit = {
    val iLimit = off+len
    var i = indexOfFirstIn(bs,off,len)
    while (i >= 0) {
      f(i)
      i += patternLength
      if (i>=iLimit) return
      i = indexOfFirstIn(bs,i,len - (i-off))
    }
  }

  // since we use curried functions we can't overload

  def foreachIndexInArray(bs: Array[Byte])(f: Int=>Unit): Unit = foreachIndexIn(bs,0,bs.length)(f)

  def foreachIndexInByteRange(bs: ByteSlice)(f: Int=>Unit): Unit = foreachIndexIn(bs.data,bs.off,bs.len)(f)

  def foreachIndexInString(s: String)(f: Int=>Unit): Unit = {
    val sdb = getStringDataBuffer(s.length)
    sdb += s
    foreachIndexIn(sdb.data,0,sdb.len)(f)
  }

  //--- first occurrence

  def indexOfFirstIn(bs: Array[Byte], off: Int, len: Int): Int = {
    val pl1 = patternLength-1
    val jMax = patternOffset + pl1
    val lim = off+len

    var i=off
    var l=i+pl1

    while (l < lim){
      var j = jMax
      var k = pl1
      while (bs(i+k) == pattern(j)){
        j -= 1
        k -= 1
        if (k < 0) return i
      }
      i += shift(bs(l) & 0xff) & 0xff
      l = i + pl1
    }

    -1 // nothing found
  }

  //--- various overloads for related input data
  def indexOfFirstIn (bs: Array[Byte]): Int = indexOfFirstIn(bs,0,bs.length)

  def indexOfFirstIn (bs: ByteSlice): Int = indexOfFirstIn(bs.data,bs.off,bs.len)

  def indexOfFirstIn (s: String): Int = {
    val sdb = getStringDataBuffer(s.length)
    sdb += s
    indexOfFirstIn(sdb.data,0,sdb.len)
  }

  def indexOfFirstInRange(bs: Array[Byte], i0: Int, i1: Int): Int = indexOfFirstIn(bs,i0,i1-i0)


  /**
    * find start index of last possible pattern prefix at end of given data range
    *
    * this can be used to compute read barriers in streams/buffers to make sure we don't miss
    * markers that are only partially available at the end of the currently buffered/read data
    */
  def indexOfLastPatternPrefixIn(bs: Array[Byte], off: Int, len: Int): Int = {
    val p = pattern
    val iMax = off + len

    // i: buffer index, j: pattern index
    @tailrec def matchPrefix (i: Int, j: Int): Int = {
      if (i == iMax) {
        if (j==0) -1 else iMax - j
      } else {
        if (p(j) == bs(i)) {
          matchPrefix(i+1, j+1)
        } else {
          matchPrefix(i+1, 0)
        }
      }
    }

    matchPrefix(Math.max(off,iMax-p.length),0)
  }

  def indexOfLastPatternPrefixIn (bs: Array[Byte]): Int = indexOfLastPatternPrefixIn(bs,0,bs.length)

  def indexOfLastPatternPrefixIn (slice: ByteSlice): Int = indexOfLastPatternPrefixIn(slice.data, slice.off, slice.len)

  def indexOfLastPatternPrefixIn (s: String): Int = {
    val sdb = getStringDataBuffer(s.length)
    sdb += s
    indexOfLastPatternPrefixIn(sdb.data,0,sdb.len)
  }
}
