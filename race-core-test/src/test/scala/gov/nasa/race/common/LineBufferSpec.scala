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

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import java.io.{File, FileInputStream}
import java.util.zip.GZIPInputStream

/**
  * reg test for LineBuffer
  */
class LineBufferSpec extends AnyFlatSpec with RaceSpec {

  "a LineBuffer" should "read a known plain input file" in {
    val is = new FileInputStream(baseResourceFile("sbs.csv"))

    val lb = new LineBuffer(is,10000, 512)
    var i=0
    while (lb.nextLine()) {
      println(s"${i+1}: [${lb.off},${lb.len} <${lb.dataLength}] = \t\t'${lb.asString}'")
      i += 1
    }

    assert(i == 200)
  }

  "a LineBuffer" should "read a known compressed input file" in {
    val fis = new FileInputStream(baseResourceFile("sbs.csv.gz"))
    val is = new GZIPInputStream(fis)

    val lb = new LineBuffer(is,1000000, 512)
    var i=0
    while (lb.nextLine()) {
      println(s"${i+1}: [${lb.off},${lb.len} <${lb.dataLength}] = '${lb.asString}'")
      i += 1
    }

    assert(i == 100)
  }

}
