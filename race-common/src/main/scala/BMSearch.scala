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

import java.util.{Arrays => JArrays}

import scala.annotation.tailrec


/**
  * simple ASCII Boyer Moore search using Array[Char] objects
  * Note that we only support patterns consisting of ASCII chars (<256)
  */
class BMSearch (val pattern: Array[Char]) {

  def this (pat: String) = this(pat.toCharArray)

  val jumpTable = initJumpTable(pattern)

  protected def initJumpTable (pat: Array[Char]) = {
    val m = pat.length
    val jumps = new Array[Byte](256)
    JArrays.fill(jumps,m.toByte)

    var i=0
    while (i<m){
      jumps(pat(i)) = i.toByte
      i += 1
    }
    jumps
  }

  def indexOfFirst(cs: Array[Char]): Int = indexOfFirst(cs,0,cs.length)
  def indexOfFirst(cs: Array[Char], i0: Int): Int = indexOfFirst(cs,i0,cs.length)

  def indexOfFirst(cs: Array[Char], i0: Int, i1: Int): Int = {
    val t = jumpTable
    val p = pattern
    val m = p.length
    val imax = Math.min(cs.length,i1) - m

    @tailrec def jump (i: Int, j: Int): Int = {
      val c = cs(i+j)
      if (p(j) != c) {
        val r = if (c > t.length) m else t(c)
        return Math.max(1, j - r)
      }
      if (j == 0) 0 else jump(i,j-1)
    }

    var i = i0
    while (i <= imax){
      val skip = jump(i,m-1)
      if (skip > 0) i += skip else return i
    }
    -1 // nothing found
  }
}
