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

import gov.nasa.race.util.StringUtils
import java.security.MessageDigest


/**
  * create MD5 checksums for Strings
  *
  * Note - this implementation is NOT thread safe, it tries to avoid allocation to
  * support highly repetitive use cases that involve long Strings. We also compute the hash
  * independent of charset encodings, to support hash comparison across the network
  */
class MD5Checksum (bufSize: Int=1024) {

  var sdb: StringDataBuffer = null  // initialized on demand

  val md = MessageDigest.getInstance("MD5")
  val hb = new Array[Byte](16) // MD5 computes 128 bit hashes

  def getHexChecksum (s: String): String = {
    if (sdb == null) {
      sdb = new Utf8Buffer(Math.max(s.length,bufSize))
    } else {
      sdb.clear()
    }
    val bs = sdb
    bs += s
    getHexChecksum(sdb)
  }

  def getHexChecksum(cs: Array[Char]): String = {
    val n = cs.length
    var i = 0

    md.reset
    while (i < n) {
      val c= cs(i)
      if (c > 255){
        md.update(((c >> 8) & 0xff).toByte)
        md.update((c & 0xff).toByte)
      } else {
        md.update(c.toByte)
      }
      i += 1
    }
    md.digest(hb,0,hb.length)
    StringUtils.toHexString(hb)
  }

  def getHexChecksum(bs: Array[Byte], off: Int, len: Int): String = {
    var i = off
    val iMax = off + len

    md.reset
    while (i < iMax) {
      md.update(bs(i))
      i += 1
    }
    md.digest(hb,0,hb.length)
    StringUtils.toHexString(hb)
  }

  def getHexChecksum(bs: Array[Byte]): String = getHexChecksum(bs,0,bs.length)

  def getHexChecksum(slice: ByteSlice): String = getHexChecksum(slice.data,slice.off,slice.len)
}
