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
import scala.language.implicitConversions

/**
  * MurmurHash64 implementation
  */
object MurmurHash64 {

  @inline implicit def byteToLong (b: Byte): Long = (b & 0xffL)
  @inline implicit def charToLong (c: Char): Long = (c & 0xffL)

  val seed: Int = 0xe17a1465

  // this assumes utf8 bytes
  // note we might have to create explicit Byte/Char versions as the implicit arg does incur some runtime costs
  def hash [@specialized(Byte,Char)T<:AnyVal](data: Array[T], off: Int, len: Int) (implicit ev: T=>Long): Long = {
    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (len * m)

    val nOctets = len / 8

    var i = 0
    var j = off
    while (i < nOctets) {
      var k: Long = (data(j).toLong); j+= 1
      k += data(j).toLong << 8   ; j += 1
      k += data(j).toLong << 16  ; j += 1
      k += data(j).toLong << 24  ; j += 1
      k += data(j).toLong << 32  ; j += 1
      k += data(j).toLong << 40  ; j += 1
      k += data(j).toLong << 48  ; j += 1
      k += data(j).toLong << 56  ; j += 1

      k *= m
      k ^= k >>> r
      k *= m

      h ^= k
      h *= m

      i += 1
    }

    ((len % 8): @switch) match {
      case 7 =>
        h ^= data(j+6).toLong << 48
        h ^= data(j+5).toLong << 40
        h ^= data(j+4).toLong << 32
        h ^= data(j+3).toLong << 24
        h ^= data(j+2).toLong << 16
        h ^= data(j+1).toLong << 8
        h ^= data(j).toLong  ; h *= m
      case 6 =>
        h ^= data(j+5).toLong << 40
        h ^= data(j+4).toLong << 32
        h ^= data(j+3).toLong << 24
        h ^= data(j+2).toLong << 16
        h ^= data(j+1).toLong << 8
        h ^= data(j).toLong  ; h *= m
      case 5 =>
        h ^= data(j+4).toLong << 32
        h ^= data(j+3).toLong << 24
        h ^= data(j+2).toLong << 16
        h ^= data(j+1).toLong << 8
        h ^= data(j).toLong  ; h *= m
      case 4 =>
        h ^= data(j+3).toLong << 24
        h ^= data(j+2).toLong << 16
        h ^= data(j+1).toLong << 8
        h ^= data(j).toLong  ; h *= m
      case 3 =>
        h ^= data(j+2).toLong << 16
        h ^= data(j+1).toLong << 8
        h ^= data(j).toLong  ; h *= m
      case 2 =>
        h ^= data(j+1).toLong << 8
        h ^= data(j).toLong  ; h *= m
      case 1 =>
        h ^= data(j).toLong; h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }


  @inline def hash (data: Array[Byte]): Long = hash(data,0,data.length)
  @inline def hash (data: Array[Char]): Long = hash(data,0,data.length)
}
