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
  * support for path-like identifiers that are stored as String objects
  * (using a Unix-filesystem model without the need for a full java.nio.file.Path support)
  */
object PathIdentifier {

  //--- match support for identifier (not sure this belongs here)

  trait Matcher {
    def pattern: String
    def matches (id: CharSequence): Boolean

    //--- combinators
    def or (m: Matcher): Matcher = OrMatcher(s"$pattern,${m.pattern}", this, m)
    def and (m: Matcher): Matcher = AndMatcher(s"$pattern + ${m.pattern}", this, m)
  }

  case class OrMatcher(pattern: String, head: Matcher, tail: Matcher) extends Matcher {
    override def matches (id: CharSequence): Boolean =  head.matches(id) || tail.matches(id)
  }

  case class AndMatcher(pattern: String, head: Matcher, tail: Matcher) extends Matcher {
    override def matches (id: CharSequence): Boolean =  head.matches(id) && tail.matches(id)
  }

  case class RegexMatcher (pattern: String, regex: Regex) extends Matcher {
    def matches (id: CharSequence): Boolean = regex.matches(id)
  }

  case class StringMatcher (pattern: String) extends Matcher {
    def matches (id: CharSequence): Boolean = pattern.equals(id)
  }

  //--- singletons

  object allMatcher extends Matcher {
    def pattern = "<all>"
    def matches (id: CharSequence): Boolean = true

    override def or (m: Matcher): Matcher = this // no point to have alternatives to <all>
    override def and (m: Matcher): Matcher = m // <all> always matches
  }

  object noneMatcher extends Matcher {
    def pattern = "<none>"
    def matches (id: CharSequence): Boolean = false

    override def or (m: Matcher): Matcher = m
    override def and (m: Matcher): Matcher = this // <none> never matches
  }

  //--- constructors

  def globMatcher (pattern: String): Matcher = new RegexMatcher( pattern, Glob.glob2Regex(pattern))

  def regexMatcher (pattern: String): Matcher = new RegexMatcher( pattern, new Regex(pattern))


  //--- identifier construction / de-construction

  def parent (path: CharSequence): String = {
    val len = path.length
    var i = len - 1

    while (i >= 0) {
      if (path.charAt(i) == '/') {
        return if (len == 1) "" else path.subSequence(0,i).toString
      }
      i -= 1
    }
    ""
  }

  def name (path: CharSequence): String = {
    val len = path.length
    var i = len - 1

    while (i >= 0) {
      if (path.charAt(i) == '/') {
        return if (len == 1) "" else path.subSequence(i+1, len).toString
      }
      i -= 1
    }
    path.toString
  }

  /**
    * this assumes pattern is already canonical, i.e. there is either a single '.' path prefix or potentially
    * multiple '..' prefixes, but nothing like "./.././blah" or "a/../b"
    */
  def resolve (pattern: CharSequence, baseId: String): String = {
    val len = pattern.length
    @inline def peekChar (i: Int): Char = if (i < len) pattern.charAt(i) else 0

    //--- corner cases
    if (len == 0) return baseId
    if (baseId == null || baseId.isEmpty) return pattern.toString

    var c = pattern.charAt(0)
    if (c == '/') return pattern.toString                       // /<pattern>  - already absolute

    if (c == '.') {
      if (len == 1) return baseId                               // single .

      c = pattern.charAt(1)
      if (c == '/') return baseId + pattern.subSequence(1, len) // ./<pattern>

      if (c == '.') {
        if (len == 2) return parent(baseId)                     // single ..

        c = pattern.charAt(2)
        if (c == '/') {
          var p = parent(baseId)
          var i = 2
          while (peekChar(i + 1) == '.' && peekChar(i + 2) == '.' && peekChar(i + 3) == '/') {
            i += 3
            p = parent(p)
          }
          return p + pattern.subSequence(i, len)               // {../}<pattern>
        }
      }
    } else if (c == '*') {
      if (len >1) {
        c = pattern.charAt(2)
        if (c == '*') return pattern.toString
      }

      return baseId + pattern
    }

    baseId + '/' + pattern  // relative path, prepend baseId
  }


  def isAbsolute (id: CharSequence): Boolean = id.length > 0 && id.charAt(0) == '/'
  def isRoot (id: CharSequence): Boolean = id.length == 1 && id.charAt(0) == '/'
  def isCurrent (id: CharSequence): Boolean = id.length == 1 && id.charAt(0) == '.'
  def isParent (id: CharSequence): Boolean = id.length == 2 && id.charAt(0) == '.' && id.charAt(1) == '.'

  def isGlobPattern (s: CharSequence): Boolean = {
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '*' || c == '?' || c == '[' || c == '!' || c == '{') return true
      i += 1
    }
    false
  }
}
