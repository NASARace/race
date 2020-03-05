/*
 * Copyright (c) 2020, United States Government, as represented by the
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

object SlicePathMatcher {
  final val MatchOneLevel = ConstUtf8Slice("*")
  final val MatchNLevels = ConstUtf8Slice("**")

  def compileSpec (pathSpec: String, sep: Byte): Array[CharSeqByteSlice] = {

    def elemSlice (s: CharSeqByteSlice, iLast: Int, i: Int): CharSeqByteSlice = {
      val ss = s.createSubSequence(iLast,(i-iLast))
      if (ss == MatchOneLevel) MatchOneLevel
      else if (ss == MatchNLevels) MatchNLevels
      else ss
    }

    val s = ConstUtf8Slice(pathSpec)
    var n = s.occurrencesOf(sep) + 1
    var iLast=0
    if (s(0) == sep) {
      n -= 1
      iLast += 1
    }

    val elems = new Array[CharSeqByteSlice](n)
    var j=0
    var i = s.indexOf(sep,iLast)
    while (i > 0) {
      elems(j) = elemSlice(s,iLast,i)
      j += 1
      iLast = i+1
      i = s.indexOf(sep,iLast)
    }
    elems(j) = elemSlice(s,iLast,s.len)

    elems
  }

  def apply (spec: String,sep: Byte='/') = new SlicePathMatcher(compileSpec(spec,sep))
  def apply (s: CharSeqByteSlice*) = new SlicePathMatcher(s.toArray)
  def apply (s: Array[String]) = new SlicePathMatcher(s.map(ConstUtf8Slice(_)))
}
import SlicePathMatcher._

/**
  * a matcher for a sequence of slices supporting wildcards:
  *
  *  - '*'  matches any single level
  *  - '**' matches 1..N levels (it has to be either in leftmost position or
  *         has to be preceded by a concrete path element)
  *
  * matching always starts from the last (rightmost) element backwards
  *
  * this represents a subset of regular expressions that can be used to check paths (e.g.
  * for XML elements)
  */
class SlicePathMatcher(val elements: Array[CharSeqByteSlice]) {

  val top = elements.length-1
  lazy val pathString: String = elements.mkString("/")

  override def toString: String = s"""PathPattern(${elements.mkString(",")})"""

  def matchesPathFunc (pathDepth: Int)(pathFunc: Int=>CharSeqByteSlice): Boolean = {
    var i = top
    var barrier: CharSeqByteSlice = null // next concrete element to match
    val elements = this.elements

    @inline def getBarrier: CharSeqByteSlice = {
      i -= 1
      while (i >= 0){
        val e = elements(i)
        if (e(0) == '*') i -= 1 else return e
      }
      null
    }

    if (elements(i) eq MatchNLevels){
      barrier = getBarrier
      if (barrier eq null) return true // no concrete parent left
    }

    var j = pathDepth-1
    while (i >= 0 && j >= 0) {
      if (barrier ne null) {
        if (barrier == pathFunc(j)) {
          i -= 1
          barrier = null
        }

      } else { // no barrier
        val e = elements(i)
        if (e eq MatchOneLevel) {
          i -= 1 //  one level wildcard match
        } else if (e eq MatchNLevels) {
          barrier = getBarrier
          if (barrier eq null) return true // no concrete parent left
        } else {
          if (e != pathFunc(j)) return false // concrete mismatch
          i -= 1
        }
      }

      j -= 1
    }

    i < 0 && j < 0
  }

  def matches (path: Array[String]): Boolean = {
    matchesPathFunc(path.length){ i=>ConstUtf8Slice(path(i)) }
  }

  def matches (data: Array[Byte], slice: MutCharSeqByteSlice, stack: RangeStack): Boolean = {
    matchesPathFunc(stack.size){ i=>
      slice.set(data,stack.offsets(i),stack.lengths(i))
      slice
    }
  }

  def matches (path: Seq[CharSeqByteSlice]): Boolean = {
    matchesPathFunc(path.size){ path(_) }
  }

  def matchesStringPath (ss: String*): Boolean = {
    matchesPathFunc(ss.size){ i=> ConstUtf8Slice(ss(i))}
  }

  //.. and some more in the future
}
