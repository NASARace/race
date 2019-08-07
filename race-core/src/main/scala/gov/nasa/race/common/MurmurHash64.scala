/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import scala.annotation.switch

/**
  * MurmurHash64 implementation
  */
object MurmurHash64 {
  val seed: Int = 0xe17a1465

  // this assumes utf8 bytes
  def hash (data: Array[Byte], off: Int, len: Int): Long = {
    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (len * m)

    val nOctets = len / 8

    var i = 0
    var j = off
    while (i < nOctets) {
      var k: Long = (data(j) & 0xffL); j+= 1
      k += (data(j) & 0xffL)<<8   ; j += 1
      k += (data(j) & 0xffL)<<16  ; j += 1
      k += (data(j) & 0xffL)<<24  ; j += 1
      k += (data(j) & 0xffL)<<32  ; j += 1
      k += (data(j) & 0xffL)<<40  ; j += 1
      k += (data(j) & 0xffL)<<48  ; j += 1
      k += (data(j) & 0xffL)<<56  ; j += 1

      k *= m
      k ^= k >>> r
      k *= m

      h ^= k
      h *= m

      i += 1
    }

    ((len % 8): @switch) match {
      case 7 =>
        h ^= (data(j+6) & 0xffL) << 48
        h ^= (data(j+5) & 0xffL) << 40
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 6 =>
        h ^= (data(j+5) & 0xffL) << 40
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 5 =>
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 4 =>
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 3 =>
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 2 =>
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 1 =>
        h ^= (data(j)   & 0xffL); h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }

  // this duplication sucks, but a generic version would incur runtime costs
  def hashASCII (data: Array[Char], off: Int, len: Int): Long = {
    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (len * m)

    val nOctets = len / 8

    var i = 0
    var j = off
    while (i < nOctets) {
      var k: Long = (data(j) & 0xffL); j+= 1
      k += (data(j) & 0xffL)<<8   ; j += 1
      k += (data(j) & 0xffL)<<16  ; j += 1
      k += (data(j) & 0xffL)<<24  ; j += 1
      k += (data(j) & 0xffL)<<32  ; j += 1
      k += (data(j) & 0xffL)<<40  ; j += 1
      k += (data(j) & 0xffL)<<48  ; j += 1
      k += (data(j) & 0xffL)<<56  ; j += 1

      k *= m
      k ^= k >>> r
      k *= m

      h ^= k
      h *= m

      i += 1
    }

    ((len % 8): @switch) match {
      case 7 =>
        h ^= (data(j+6) & 0xffL) << 48
        h ^= (data(j+5) & 0xffL) << 40
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 6 =>
        h ^= (data(j+5) & 0xffL) << 40
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 5 =>
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 4 =>
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 3 =>
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 2 =>
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL); h *= m
      case 1 =>
        h ^= (data(j)   & 0xffL); h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }


  // this needs to produce the same values as the Array[Byte] version for utf8 encoded chars
  def hash (data: Array[Char], off: Int, len: Int): Long = {
    if (isASCII(data,off,len)) hashASCII(data,off,len)
    else 0
  }



  def isASCII (data: Array[Char], off: Int, len: Int): Boolean = {
    val i1 = off+len
    var i = off
    while (i < i1) {
      if ((data(i) & 0xff00) != 0) return false
      i += 1
    }
    true
  }

  @inline def hash (data: Array[Byte]): Long = hash(data,0,data.length)
  @inline def hash (data: Array[Char]): Long = hash(data,0,data.length)
}
