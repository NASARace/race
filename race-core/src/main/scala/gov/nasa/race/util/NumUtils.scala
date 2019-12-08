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
package gov.nasa.race.util

/**
  * utility object with number conversions
  */
object NumUtils {

  final val hexChars = Array[Byte] ('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')


  // return true if full number was converted, false if truncated
  def intToHexPadded (n: Int, bs: Array[Byte], len: Int): Boolean = {
    var i = Math.min(len,bs.length)-1
    var a: Long = n & 0xffffffffL

    while (i >= 0) {
      bs(i) = hexChars((a & 0xf).toInt)
      a >>= 4
      i -= 1
    }

    (a == 0)
  }

  @inline def intToHexPadded (n: Int, bs: Array[Byte]): Boolean = intToHexPadded(n,bs,bs.length)

  // return true if full number was converted, false if truncated
  def longToHexPadded (n: Long, bs: Array[Byte], len: Int): Boolean = {
    var i = Math.min(len,bs.length)-1
    var a: Long = n

    while (i >= 0) {
      bs(i) = hexChars((a & 0xf).toInt)
      a >>= 4
      i -= 1
    }

    (a == 0)
  }

  @inline def longToHexPadded (n: Long, bs: Array[Byte]): Boolean = longToHexPadded(n,bs,bs.length)

}
