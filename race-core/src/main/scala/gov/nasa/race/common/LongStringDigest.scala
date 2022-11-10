/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import java.security.MessageDigest
import scala.collection.mutable

object LongStringDigest {
  val hex: Array[Byte] = Array( '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
}
import LongStringDigest._

class LongStringDigest(val maxLength: Int) {
  private val md = MessageDigest.getInstance("MD5")
  private val mdLen = md.getDigestLength
  private val buf: Array[Byte] = new Array(mdLen*2)

  def hash (s: String): String = {
    if (s.length < maxLength) {
      s
    } else {
      md.reset()
      val bs = md.digest(s.getBytes)
      var i = 0;
      var j = 0;
      while (i<mdLen) {
        val n = (bs(i) & 0xff)
        buf(j) = hex(n >> 4)
        j += 1
        buf(j) = hex(n & 0xf)
        j += 1
        i += 1
      }
      new String(buf)
    }
  }
}
