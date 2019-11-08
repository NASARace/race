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
  * hash function for ascii strings and buffers with not more than 8 bytes/chars
  * for which hash functions such as MurmurHash64 are not only significantly more expensive but also
  * can cause collisions.
  */
object ASCII8Hash64 {

  def hashBytes (data: Array[Byte], off: Int, len: Int): Long = {
    if (len > 8) throw new IllegalArgumentException("byte buffer exceeding max length 8")

    var h: Long = data(off) & 0xffL
    val i1 = off+len
    var i = off+1

    while (i < i1){
      h <<= 8
      h |= (data(i) & 0xffL)
      i += 1
    }
    h
  }
  @inline def hashBytes (data: Array[Byte]): Long = hashBytes(data,0,data.length)

  // todo - check valid ASCII range
  def hashChars (data: Array[Char], off: Int, len: Int): Long = {
    if (len > 8) throw new IllegalArgumentException("char buffer exceeding max length 8")

    var h: Long = data(off) & 0xffL
    val i1 = off+len
    var i = off+1

    while (i < i1){
      h <<= 8
      h |= (data(i) & 0xffL)
      i += 1
    }
    h
  }
  @inline def hashChars (data: Array[Char]): Long = hashChars(data,0,data.length)

  // todo - check valid ASCII range
  def hashString (s: String): Long = {
    val len = s.length
    if (len > 8) throw new IllegalArgumentException("string exceeding max length 8")
    if (len == 0) return 0

    var h: Long = s.charAt(0) & 0xffL
    val i1 = len
    var i = 1

    while (i < i1){
      h <<= 8
      h |= (s.charAt(i) & 0xffL)
      i += 1
    }
    h
  }
}
