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

import scala.util.matching.Regex

/**
  * utility object to convert Unix glob patterns into Regex instances
  *
  * TODO - still missing '..' path expansion and general !(..) negation
  */
object Glob {

  def glob2Regex (glob: CharSequence): Regex = {
    val len = glob.length()
    val buf = new StringBuilder("^")
    var inAltGroup = false

    var i=0
    while (i < len) {
      val c = glob.charAt(i)
      c match {
        case '!' | '$' | '(' | ')' | '+' | '.' | '^' | '/' =>
          buf += '\\'
          buf += c
        case '?' => buf += '.'
        case '{' => buf += '('; inAltGroup = true
        case '}' => buf += ')'; inAltGroup = false
        case '[' => i = copyCharSetAt(glob,i,buf)

        case ',' => if (inAltGroup) buf += '|' else buf += c
        case '*' =>
          val i0 = i
          val prev = if (i > 0) glob.charAt(i-1) else '/'
          i += 1
          while (i < len && glob.charAt(i) == '*')i += 1
          val next = if (i < len) glob.charAt(i)  else '/'
          val isMultiElement = ((i-i0 > 1) && prev == '/' && next == '/')
          i -= 1

          if (isMultiElement) {
            buf ++= "((?:[^/]*(?:\\/|$))*)"
            i += 1
          } else {
            buf ++= "([^/]*)"
          }
        case _ => buf += c
      }
      i += 1
    }

    buf += '$'
    new Regex(buf.toString())
  }

  // char set specs don't need to be translated and they don't nest so they can be copied literally
  def copyCharSetAt (glob: CharSequence, i0: Int, buf: StringBuilder): Int = {
    var i = i0+1
    val len = glob.length()

    buf += '['
    var c = glob.charAt(i)
    if (c == '!') c = '^' // use '^' for regex charset negation

    while (i < len && c != ']') {
      buf += c
      i += 1
      if (c == '\\') {
        buf += glob.charAt(i)
        i += 1
      }
      c  = glob.charAt(i)
    }
    buf += ']'
    i
  }

  @inline def isStartingGlobChar (c: Char): Boolean = {
    (c == '*' || c == '?' || c == '[' || c == '!' || c == '{')
  }

  def isGlobPattern (s: CharSequence): Boolean = {
    var i = 0
    while (i < s.length) {
      if (isStartingGlobChar(s.charAt(i))) return true
      i += 1
    }
    false
  }

  /**
    * a glob to regex translation that assumes path-like elements that can be relative to a given base path.
    * TODO - this only handles relative->absolute and "./" replacement for now, both at the top level (glob prefix)
    * and at nested alternative level. We still need "../"
    * Note - this implementation only supports 64 levels, but this should far exceed any realistic globs
    */
  def resolvedGlob2Regex (glob: CharSequence, base: String): Regex = {
    val len = glob.length()
    val buf = new StringBuilder("^")
    var inAltGroup = false

    var resolveLevel: Long = 0L
    var lvl = 0
    var i = 0

    // filter out corner cases
    if (len == 0) return new Regex("^$")
    if (len == 1 && glob.charAt(0) == '.') return new Regex(s"^$base$$")

    @inline def peekChar(i: Int): Char = if (i < len) glob.charAt(i) else 0
    @inline def markResolved(): Unit = resolveLevel |= (1L<<lvl)
    @inline def unmarkResolved(): Unit = resolveLevel &= ~(1L<<lvl)
    @inline def pushResolveLevel(): Unit = lvl += 1
    @inline def popResolveLevel(): Unit = { resolveLevel &= ~(1L<<lvl); lvl -= 1 }

    def checkIfResolvedDot: Boolean = {
      if (peekChar(i+1) == '/') {
        buf.append(base)
        buf.append('/')
        i += 1
        markResolved()
        true
      } else false
    }

    def checkIfResolved(c: Char): Unit = {
      if (resolveLevel == 0) {
        if (c == '/') {
          markResolved()
        } else if (!isStartingGlobChar(c)) { // prepend base and go on
          buf.append(base)
          buf.append('/')
          markResolved()
        }
      }
    }

    while (i < len) {
      val c = glob.charAt(i)

      c match {
        case '!' | '$' | '(' | ')' | '+' | '^' =>
          buf += '\\'
          buf += c

        case '.' =>
          if (!checkIfResolvedDot) buf.append("\\.")

        case '?' =>
          buf += '.'

        case '{' =>
          buf += '('
          inAltGroup = true
          pushResolveLevel()

        case ',' =>
          if (inAltGroup) {
            buf += '|'
            unmarkResolved()
          } else buf += c

        case '}' =>
          buf += ')'
          inAltGroup = false
          popResolveLevel()

        case '[' =>
          i = copyCharSetAt(glob,i,buf) // FIXME - what if the charset includes a '/' and hence only conditionally resolves?

        case '*' =>
          val i0 = i
          val prev = if (i > 0) glob.charAt(i-1) else '/'
          i += 1
          while (i < len && glob.charAt(i) == '*')i += 1
          val next = if (i < len) glob.charAt(i)  else '/'
          val isMultiElement = ((i-i0 > 1) && prev == '/' && next == '/')
          i -= 1

          if (isMultiElement) {
            buf ++= "((?:[^/]*(?:\\/|$))*)"
            i += 1
          } else {
            buf ++= "([^/]*)"
          }

        case _ =>
          checkIfResolved(c)
          buf += c
      }
      i += 1
    }

    buf += '$'
    new Regex(buf.toString())
  }
}
