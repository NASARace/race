/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import gov.nasa.race.util.FileUtils

/**
  * test application for SearchStream
  *
  * this is not (yet) a regression test because meaningful test data is usually too large to be kept in
  * the repository
  *
  * assuming files are gzipped and only contain match sections, results can be verified with
  *   - number of matches:     gunzip -c <pathname> | grep -F -o <pattern> | wc -l
  *   - char size of chunks:   gunzip -c <pathname> | wc --chars
  */
object SearchStreamTest {

  def main (args: Array[String]): Unit = {
    if (args.length < 3) {
      println("usage: SearchStreamTest skip|read|print <pathName> <pattern>")
      return
    }

    val pattern = new BMSearch(args(2))

    FileUtils.inputStreamFor(args(1),8192) match {
      case Some(stream) =>
        val ss = new SearchStream(stream,8192)
        try {
          args(0) match {
            case "skip" => skipTest(ss,pattern)
            case "read" => readTest(ss,pattern)
            case "print" => printTest(ss,pattern)
            case other => println(s"unknown operation $other")
          }
        } finally {
          ss.close
          stream.close
        }
      case None => println(s"file ${args(1)} not found or empty")
    }
  }

  def skipTest (ss: SearchStream, pattern: BMSearch) = {
    var n = 0
    println(s"skipping through stream...")
    if (ss.skipTo(pattern)){
      n += 1
      while (ss.skipTo(pattern,pattern.patternLength)) n += 1
    }
    println(s"found $n matches")
  }

  def checkSingleMatch (pattern: BMSearch, bs: Array[Byte], i0: Int, i1: Int): Boolean = {
    val i = pattern.indexOfFirstInRange(bs,i0,i1)
    if (i < 0) {
      false // we need one match
    } else {
      pattern.indexOfFirstInRange(bs,i+pattern.patternLength,i1) < 0  // and only one.
    }
  }

  def readTest (ss: SearchStream, pattern: BMSearch) = {
    var n = 0
    var nRead: Long = 0
    println(s"reading through stream...")
    if (ss.skipTo(pattern)){
      while (ss.readTo(pattern,pattern.patternLength)) {
        n += 1
        val res = ss.readResult
        assert( res.isSuccess)
        assert( res.len > 0)
        assert(checkSingleMatch(pattern, res.cs,res.startIdx,res.endIdx))
        nRead += res.len
      }
    }
    println(s"found $n matches with $nRead chars")
  }

  // not really a test, just a validator
  def printTest (ss: SearchStream, pattern: BMSearch) = {
    var n = 0
    var nRead: Long = 0
    println(s"printing stream chunks...")
    if (ss.skipTo(pattern)){
      while (ss.readTo(pattern,pattern.patternLength)) {
        n += 1
        val res = ss.readResult
        println(s"----------------------------- chunk $n at $nRead:")
        println(res.asString)
        nRead += res.len
      }
    }
    println("================================")
    println(s"found $n matches with $nRead chars")
  }
}
