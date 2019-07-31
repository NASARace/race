/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.io.{File, FileOutputStream, PrintStream}

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for MappedByteBufferInputStream
  */
class MappedByteBufferInputStreamSpec extends AnyFlatSpec with RaceSpec {

  val line = "0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345\n".getBytes
  val nLines = 1000000


  def createTestFile: File = {
    val f = emptyTestOutputFile("mappedbb.dat")
    val fs = new FileOutputStream(f)

    //--- each record has 70 bytes
    for (i <- 0 until nLines) fs.write(line)
    fs.close
    f
  }

  "a MappedByteBuffer" should "reproduce known data" in {

    println("creating test file (this might take a while)..")
    val testFile = createTestFile

    println("now reading test file..")
    val mbbis = new MappedByteBufferInputStream(testFile.getPath)

    val buf = new Array[Byte](line.length)

    var i = 0
    var len: Int = mbbis.read(buf)
    while(len > 0){
      assert( len == line.length)
      i += 1

      for (j <- 0 until line.length) {
        assert( buf(j) == line(j))
        buf(j) = '?'
      }
      len = mbbis.read(buf)
    }

    assert(i == nLines)

    testFile.delete
  }
}
