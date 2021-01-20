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
        case '!' | '$' | '(' | ')' | '+' | '.' | '^' | '/' =>   buf += '\\'; buf += c
        case '?' => buf += '.'
        case '{' => buf += '('; inAltGroup = true
        case '}' => buf += ')'; inAltGroup = false
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
