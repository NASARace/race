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
package gov.nasa.race.air

import gov.nasa.race.util.FileUtils

/**
  * test application for DWArchiveReader implementations
  * This is not (yet) a reg test because the test data is too large for the repo
  */
object DWArchiveReaderTest {

  def main (args: Array[String]): Unit = {
    //val args = Array("asdex", "../data/ACA759-070717/asdex.xml.gz")

    if (args.length < 2) {
      println("usage: DWArchiveReaderTest asdex|sfdps|tais|tfmdata <pathname>")
      return
    }

    FileUtils.inputStreamFor(args(1),8192) match {
      case Some(stream) =>
        args(0) match {
          case "asdex" => test(new AsdexDWArchiveReader(stream))
          case "sfdps" => test(new SfdpsDWArchiveReader(stream))
          case "tais" => test(new TaisDWArchiveReader(stream))
          case "tfmdata" => test(new TfmdataDWArchiveReader(stream))
          case other => println(s"unknown archive type $other")
        }

      case None => println(s"archive file ${args(1)} not found or empty")
    }
  }

  def test(ar: DWArchiveReader): Unit = {
    try {
      println("reading archive...")
      var n = 0

      while (ar.hasMoreData) {
        ar.readNextEntry match {
          case Some(e) =>
            n += 1
            val msg = e.msg.toString
            if (msg.indexOf(ar.headerStart.pattern) >= 0) { // use a different search than DWArchiveReader
              println(s"----------------- msg error in entry $n")
              println(msg)
              return
            }
          case None =>
        }
      }

      println(s"done reading $n entries")
    } finally {
      ar.close
    }

  }
}
