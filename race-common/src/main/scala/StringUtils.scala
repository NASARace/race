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
import scala.util.matching.Regex

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


  def capLength(s: String)(implicit maxStringLength: Int = 30): String = {
    if (s.length < maxStringLength) s else s.substring(0, maxStringLength-1) + "…"
  }

  def mkString[E] (it: Iterable[E], prefix: String, sep: String, postfix: String)(f: E=>String = (e:E)=>{e.toString}): String = {
    val sb = new StringBuilder(prefix)
    var i = 0
    it.foreach { e =>
      if (i>0) sb.append(sep)
      i += 1
      sb.append(f(e))
    }
    sb.append(postfix)
    sb.toString
  }

  def append(s0: String, sep: String, s: String): String = {
    if (s0 == null || s0.isEmpty) s
    else s0 + sep + s
  }

  val IntRE = """(\d+)""".r
  val QuotedRE = """"(.*)"""".r

  val digitRE = """\d""".r
  val latinLetterRE = """[a-zA-Z]""".r
  def containsDigit(s: String) = digitRE.findFirstIn(s).isDefined
  def containsLatinLetter(s: String) = latinLetterRE.findFirstIn(s).isDefined

  def globToRegex (glob: String) = {
    // TODO - should also cover '?'
    new Regex("^" + glob.replace("*", ".*") + '$').anchored
  }
}
