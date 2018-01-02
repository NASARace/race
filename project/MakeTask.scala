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

import sbt._
import scala.sys.process._

/**
  * task to run make on projects that have a Makefile
  *
  * Note that we only execute makefiles in project base dirs, not recursively down, since
  * Makefiles in project source dirs might only be intended for use in external projects
  * that copy sources
  */
object MakeTask {
  val defaultMakeCmd = "make"
  val defaultMakefile = "Makefile"

  def makeAll (makeCmd: String, baseDir: File, makefileName: String) = {
    val makefile = new File(baseDir,makefileName)
    if (makefile.isFile) {
      println(s"running 'make' in project $baseDir")
      Process(makeCmd :: "-f" :: makefile.getName :: Nil, baseDir).!
    }
  }

  def makeClean (makeCmd: String, baseDir: File, makefileName: String) = {
    val makefile = new File(baseDir, makefileName)
    if (makefile.isFile) {
      println(s"running 'make clean' in project $baseDir")
      Process(makeCmd :: "-f" :: makefile.getName :: "clean" :: Nil, baseDir).!
    }
  }
}
