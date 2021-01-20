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
  }

  class RegexMatcher (val pattern: String, regex: Regex) extends Matcher {
    def matches (id: CharSequence): Boolean = regex.matches(id)
  }

  object allMatcher extends Matcher {
    def pattern = "<all>"
    def matches (id: CharSequence): Boolean = true
  }

  object noneMatcher extends Matcher {
    def pattern = "<none>"
    def matches (id: CharSequence): Boolean = false
  }

  def globMatcher (pattern: String): Matcher = new RegexMatcher( pattern, Glob.glob2Regex(pattern))

  def regexMatcher (pattern: String): Matcher = new RegexMatcher( pattern, new Regex(pattern))


  //--- identifier construction / de-construction

  def parent (path: CharSequence): String = {
    val len = path.length
    var i = len - 1

    if (i > 1) {
      if (path.charAt(i) == '/') i -= 1
      while (i >= 0 && path.charAt(i) != '/') i -= 1
      path.subSequence(0,i).toString
    } else if (i == 0) {
      if (path.charAt(i) == '/') path.toString else ""
    } else {
      ""
    }
  }

  def resolve (pattern: CharSequence, baseId: String): String = resolve(pattern,baseId,baseId)

  def resolve (pattern: CharSequence, curId: String, baseId: String): String = {
    if (baseId != null && baseId.nonEmpty) {
      if (pattern == ".") {
        curId
      } else if (pattern == "..") {
        parent(curId)
      } else {
        if (pattern.charAt(0) == '/') {
          pattern.toString
        } else {
          if (baseId.charAt(baseId.length - 1) == '/') baseId + pattern else baseId + '/' + pattern
        }
      }
    } else pattern.toString
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
