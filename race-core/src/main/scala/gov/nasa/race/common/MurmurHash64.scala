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

/**
  * MurmurHash64 implementation
  */
object MurmurHash64 {
  val seed: Int = 0xe17a1465

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

    (len % 8) match {
      case 7 => h ^= (data(j+6) & 0xffL) << 48
      case 6 => h ^= (data(j+5) & 0xffL) << 40
      case 5 => h ^= (data(j+4) & 0xffL) << 32
      case 4 => h ^= (data(j+3) & 0xffL) << 24
      case 3 => h ^= (data(j+2) & 0xffL) << 16
      case 2 => h ^= (data(j+1) & 0xffL) << 8
      case 1 => h ^= (data(j)   & 0xffL); h *= m
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }

  def hash (data: Array[Char], off: Int, len: Int): Long = {
    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (len * m)
    val nQuartets = len / 4

    var i = 0
    var j = off
    while (i < nQuartets) {
      var k: Long = data(j) & 0xffffL; j += 1
      k |= (data(j) & 0xffffL) << 16; j += 1
      k |= (data(j) & 0xffffL) << 32; j += 1
      k |= (data(j) & 0xffffL) << 48; j += 1

      k *= m
      k ^= k >>> r
      k *= m

      h ^= k
      h *= m

      i += 1
    }

    (len % 4) match {
      case 3 => h ^= (data(j+2) & 0xffffL) << 32
      case 2 => h ^= (data(j+1) & 0xffffL) << 16
      case 1 => h ^= (data(j) & 0xffffL) << 32; h *= m
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }

  @inline def hash (data: Array[Byte]): Long = hash(data,0,data.length)
  @inline def hash (data: Array[Char]): Long = hash(data,0,data.length)
}
