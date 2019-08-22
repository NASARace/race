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
  * Bob Jenkins One-at-a-Time Hash (http://www.burtleburtle.net/bob/hash/doobs.html),
  * a simple yet sufficiently avalanching hash that doesn't require a-priori knowledge
  * of the key length and is much faster than lookup3
  *
  * the public API is designed to avoid allocation of objects for the sole purpose of computing the hash
  * (e.g. String.getBytes)
  */
object OATHash {

  @inline private def mixin (h0: Int, b: Int): Int = {
    var h = h0 + b
    h += (h << 10)
    h ^ (h >> 6)
  }

  @inline private def finalize (h0: Int): Int = {
    var h = h0 + (h0 << 3)
    h ^= (h >> 11)
    h + (h << 15)
  }

  def hash (data: Array[Byte], off: Int, len: Int): Int = {
    var h = 0
    var i = off
    var i1 = off + len

    while (i < i1) {
      h = mixin(h, (data(i) & 0xff))
      i += 1
    }

    finalize(h)
  }
  @inline def hash (data: Array[Byte]): Int = hash(data,0,data.length)

  def hash (data: Array[Char], off: Int, len: Int): Int = {
    var h: Int = 0
    var i = off
    val i1 = off + len

    while (i < i1) {
      val c = data(i)
      val c0 = c & 0xff
      h = mixin(h, c0)

      if (c != c0) {
        h = mixin(h, (c >> 8) & 0xff)
      }
      i += 1
    }
    finalize(h)
  }
  @inline def hash (data: Array[Char]): Int = hash(data,0,data.length)

  def hash (data: String): Int = {
    var h: Int = 0
    var i = 0
    val len = data.length
    while (i < len){
      val c = data.charAt(i)
      val c0 = c & 0xff
      h = mixin(h, c0)

      if (c != c0) {
        h = mixin(h, (c >> 8) & 0xff)
      }
      i += 1
    }
    finalize(h)
  }
}
