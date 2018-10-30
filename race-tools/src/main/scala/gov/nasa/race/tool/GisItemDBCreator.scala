/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.tool

import java.io._

import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.FileUtils


object GisItemDBCreator {


  //-------------------------------------

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var inFile: Option[File] = None
    var outDir: File = new File("tmp")

    requiredArg1("<pathName>", "SQL file to parse") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts
  

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      ifSome(opts.inFile) { inFile =>
        val outFile = new File(opts.outDir, FileUtils.filenameWithExtension(inFile, "bin"))
        val out = new DataOutputStream(new FileOutputStream(outFile))
        try {
        } finally {
          out.close
        }
      }
    }
  }

}
