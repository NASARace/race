/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import gov.nasa.race.test.{ChunkInputStream, RaceSpec}
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

import java.io.{File, FileInputStream, InputStream}
import java.util.zip.GZIPInputStream

/**
  * reg test for LineBuffer
  */
class LineBufferSpec extends AnyFlatSpec with RaceSpec {
  val expectedLines = FileUtils.fileContentsAsLineArray(baseResourceFile("sbs.csv"))

  def readLines (is: InputStream): Unit = {
    val lb = new LineBuffer(is,10000, 512)
    var i=0
    var line: String = null
    while (lb.nextLine()) {
      line = lb.asString.stripLineEnd
      //println(s"${i+1}: [${lb.off},${lb.len} <${lb.dataLength}] = \t\t'$line'")
      assert(line == expectedLines(i))
      i += 1
    }

    assert(i == 2000)
    assert(i == expectedLines.length)
  }

  "a LineBuffer" should "read a known plain input file" in {
    val is = new FileInputStream(baseResourceFile("sbs.csv"))
    readLines(is)
  }

  "a LineBuffer" should "read a known compressed input file" in {
    val fis = new FileInputStream(baseResourceFile("sbs.csv.gz"))
    val is = new GZIPInputStream(fis)
    readLines(is)
  }

  "a LineBuffer" should "read all lines from a randomly chunked input stream" in {
    val fis = new FileInputStream(baseResourceFile("sbs.csv"))
    val is = new ChunkInputStream(fis,120,42)
    readLines(is)
  }
}
