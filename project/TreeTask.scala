/*
 * Copyright (c) 2016, United States Government, as represented by the
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

import java.io.File

object TreeTask {
  val curDirPath = System.getProperty("user.dir")

  def apply (sourceDirectory: File) = {
    listDir(sourceDirectory, 0)
  }

  def listDir (dir: File, level: Int): Unit = {
    println(s"${indent(level)}${relPath(dir)}/")
    listSubdirs(dir,level+1)
    listFiles(dir, level+1)
  }

  def listSubdirs (dir: File, level: Int) = {
    dir.listFiles.filter(_.isDirectory).toList foreach { listDir(_,level) }
  }

  def listFiles (dir: File, level: Int) = {
    for (file <- dir.listFiles.filter(_.isFile).toList) {
      println(s"${indent(level)}${file.getName}")
    }
  }

  def indent (level: Int): String = ".   " * level
  def relPath (f: File) = f.getPath.substring(curDirPath.length+1)
}
