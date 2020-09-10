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

import java.nio.file.{FileSystem, Path, PathMatcher}

import com.google.common.jimfs.{Configuration, Jimfs}

/**
  * helper class to enforce Unix ('/' - based) Paths/PathMatchers
  */
object UnixPath {

  implicit class PathHelper (val sc: StringContext) extends AnyVal {
    def p (args: Any*): Path = {
      val strings = sc.parts.iterator
      val expressions = args.iterator
      var buf = new StringBuilder(strings.next())
      while(strings.hasNext) {
        buf.append(expressions.next())
        buf.append(strings.next())
      }
      UnixPath(buf.toString)
    }
  }

  private var interned: Map[String,Path] = Map.empty
  private val fs: FileSystem = Jimfs.newFileSystem(Configuration.unix)

  // we keep those as singletons since Path objects are somewhat expensive
  val current: Path = fs.getPath(".")
  val parent: Path = fs.getPath("..")

  def isCurrent (p: Path): Boolean = (p eq current) || (p.toString == ".")
  def isCurrent (s: String): Boolean = s == "."

  def isParent (p: Path): Boolean = (p eq parent) || (p.toString == "..")
  def isParent (s: String): Boolean = s == ".."

  def apply (s: CharSequence): Path = {
    if (s == ".") current
    else if (s == "..") parent
    else fs.getPath(s.toString)
  }

  /**
    * create only one Path instance per path string.
    */
  def intern (s: CharSequence): Path = {
    if (s == ".") current // those are always interned
    else if (s == "..") parent
    else {
      val ps = s.toString.intern()
      interned.get(ps) match {
        case Some(path) => path
        case None =>
          val p = fs.getPath(ps)
          val path = p.normalize()
          interned = interned + (ps -> path)
          if (p ne path) interned = interned + (path.toString -> path)
          path
      }
    }
  }

  def from(s: CharSequence): Path = apply(s)

  def isPattern (s: CharSequence): Boolean = {
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '*' || c == '?' || c == '[' || c == '!' || c == '{') return true
      i += 1
    }
    false
  }

  def isAbsolutePath(s: CharSequence): Boolean = { s.length > 0 && s.charAt(0) == '/'}
  def isAbsolutePathSpec(s: CharSequence): Boolean = {
   isAbsolutePath(s) || (s.length >= 2 && s.charAt(0) == '*' && s.charAt(1) == '*')
  }

  def resolvePattern (p: Path, s: CharSequence): String = {
    if (isAbsolutePathSpec(s)) {
      s.toString      
    } else {
      val pp = UnixPath(s)
      p.resolve(pp).normalize.toString
    }
  }

  def matcher (pattern: String): PathMatcher = fs.getPathMatcher(pattern)
  def globMatcher (pattern: String): PathMatcher = fs.getPathMatcher(s"glob:$pattern")
  def regexMatcher (pattern: String): PathMatcher = fs.getPathMatcher(s"regex:$pattern")

}

object AllMatcher extends PathMatcher {
  def matches (p: Path): Boolean = true
}

object NoneMatcher extends PathMatcher {
  def matches (p: Path): Boolean = false
}

case class SameMatcher(self: Path) extends PathMatcher {
  def matches (p: Path): Boolean = (p eq self) || (p == self)
}