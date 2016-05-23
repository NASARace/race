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

import scala.annotation.tailrec

/**
  * common String related functions
  */
object StringUtils {

  def stringTail(s: String, c: Char) = s.substring(Math.max(s.indexOf(c),0))

  def trimmedSplit(s: String): Seq[String] = s.split("[ ,;]+").map(_.trim)

  final val hexChar = Array('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')

  // not worth using javax.xml.bind.DatatypeConverter et al.
  def toHexString (bs: Array[Byte]): String = {
    val len = bs.length
    val cs = new Array[Char](bs.length * 2)
    @tailrec def _byteToHex(i: Int): Unit = {
      if (i < len) {
        val b = bs(i)
        val j = i * 2
        cs(j) = hexChar((b >> 4) & 0xf)
        cs(j + 1) = hexChar(b & 0xf)
        _byteToHex(i + 1)
      }
    }
    _byteToHex(0)
    new String(cs)
  }

  def padLeft (s: String, len: Int, c: Char): String = {
    val n = len - s.length
    if (n > 0){
      val sb = new StringBuilder(len)
      repeat(n) { sb.append(c) }
      sb.append(s)
      sb.toString()
    } else s
  }

  val IntRE = """(\d+)""".r
  val QuotedRE = """"(.*)"""".r
}
