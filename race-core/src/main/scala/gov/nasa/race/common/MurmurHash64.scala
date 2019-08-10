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
  *
  * this is very redundant code but unfortunately all refactoring to avoid replication would carry a significant
  * runtime performance cost
  */
object MurmurHash64 {

  val seed: Int = 0xe17a1465

  // this is bad - we have to duplicate the code so that we avoid the runtime cost of a conversion call per byte/char
  // (there is no numeric type that has a '&' operator). When compiling optimized, the generic version that uses
  // implicit conversion to Long is slower by a factor of >2
  
  def hashBytes (data: Array[Byte], off: Int, len: Int): Long = {
    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (len * m)

    val nOctets = len / 8

    var i = 0
    var j = off
    while (i < nOctets) {
      var k: Long = (data(j) & 0xffL); j+= 1
      k += (data(j) & 0xffL) << 8   ; j += 1
      k += (data(j) & 0xffL) << 16  ; j += 1
      k += (data(j) & 0xffL) << 24  ; j += 1
      k += (data(j) & 0xffL) << 32  ; j += 1
      k += (data(j) & 0xffL) << 40  ; j += 1
      k += (data(j) & 0xffL) << 48  ; j += 1
      k += (data(j) & 0xffL) << 56  ; j += 1

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
        h ^= (data(j)   & 0xffL)  ; h *= m
      case 5 =>
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)  ; h *= m
      case 4 =>
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)  ; h *= m
      case 3 =>
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)  ; h *= m
      case 2 =>
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)  ; h *= m
      case 1 =>
        h ^= (data(j)   & 0xffL)  ; h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }
  
  def hashASCIIChars (data: Array[Char], off: Int, len: Int): Long = {
    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (len * m)

    val nOctets = len / 8

    var i = 0
    var j = off
    while (i < nOctets) {
      var k: Long = (data(j) & 0xffL); j+= 1
      k += (data(j) & 0xffL) << 8   ; j += 1
      k += (data(j) & 0xffL) << 16  ; j += 1
      k += (data(j) & 0xffL) << 24  ; j += 1
      k += (data(j) & 0xffL) << 32  ; j += 1
      k += (data(j) & 0xffL) << 40  ; j += 1
      k += (data(j) & 0xffL) << 48  ; j += 1
      k += (data(j) & 0xffL) << 56  ; j += 1

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
        h ^= (data(j)   & 0xffL)
        h *= m
      case 6 =>
        h ^= (data(j+5) & 0xffL) << 40
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)
        h *= m
      case 5 =>
        h ^= (data(j+4) & 0xffL) << 32
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)
        h *= m
      case 4 =>
        h ^= (data(j+3) & 0xffL) << 24
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)
        h *= m
      case 3 =>
        h ^= (data(j+2) & 0xffL) << 16
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)
        h *= m
      case 2 =>
        h ^= (data(j+1) & 0xffL) << 8
        h ^= (data(j)   & 0xffL)
        h *= m
      case 1 =>
        h ^= (data(j)   & 0xffL)
        h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }


  def hashChars (data: Array[Char], off: Int, len: Int): Long = {
    val utf8Len = UTFx.utf8Length(data,off,len)
    var enc = UTFx.initUTF8Encoder(data,off)

    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (utf8Len * m)

    val nOctets = utf8Len / 8

    var i = 0
    while (i < nOctets) {
      var k: Long = (enc.utf8Byte & 0xffL)    ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 8        ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 16       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 24       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 32       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 40       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 48       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 56       ; enc = enc ++ data

      k *= m
      k ^= k >>> r
      k *= m

      h ^= k
      h *= m

      i += 1
    }

    ((utf8Len % 8): @switch) match {
      case 7 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 32     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 40     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 48
        h ^= k
        h *= m
      case 6 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 32     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 40
        h ^= k
        h *= m
      case 5 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 32
        h ^= k
        h *= m
      case 4 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24
        h ^= k
        h *= m
      case 3 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16
        h ^= k
        h *= m
      case 2 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8
        h ^= k
        h *= m
      case 1 =>
        h ^= (enc.utf8Byte & 0xffL)
        h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }

  def hashString (data: String): Long = {
    val utf8Len = UTFx.utf8Length(data)
    var enc = UTFx.initUTF8Encoder(data,0)

    val m: Long = 0xc6a4a7935bd1e995L
    val r: Int = 47
    var h: Long = (seed & 0xffffffffL) ^ (utf8Len * m)

    val nOctets = utf8Len / 8

    var i = 0
    while (i < nOctets) {
      var k: Long = (enc.utf8Byte & 0xffL)    ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 8        ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 16       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 24       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 32       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 40       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 48       ; enc = enc ++ data
      k += (enc.utf8Byte & 0xffL) << 56       ; enc = enc ++ data

      k *= m
      k ^= k >>> r
      k *= m

      h ^= k
      h *= m

      i += 1
    }

    ((utf8Len % 8): @switch) match {
      case 7 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 32     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 40     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 48
        h ^= k
        h *= m
      case 6 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 32     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 40
        h ^= k
        h *= m
      case 5 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 32
        h ^= k
        h *= m
      case 4 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16     ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 24
        h ^= k
        h *= m
      case 3 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8      ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 16
        h ^= k
        h *= m
      case 2 =>
        var k: Long = (enc.utf8Byte & 0xffL)  ; enc = enc ++ data
        k |= (enc.utf8Byte & 0xffL) << 8
        h ^= k
        h *= m
      case 1 =>
        h ^= (enc.utf8Byte & 0xffL)
        h *= m
      case 0 =>
    }

    h ^= h >>> r
    h *= m
    h ^ (h >>> r)
  }


  @inline def hashBytes (data: Array[Byte]): Long = hashBytes(data,0,data.length)
  @inline def hashASCIIChars (data: Array[Char]): Long = hashASCIIChars(data,0,data.length)
  @inline def hashChars (data: Array[Char]): Long = hashChars(data,0,data.length)
}
