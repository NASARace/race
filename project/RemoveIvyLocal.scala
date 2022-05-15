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

import java.io.File

object RemoveIvyLocal {

  def deleteRecursively(f: File): Unit = {
    if (f.isDirectory) {
      val content = f.listFiles()
      if (content != null && content.length > 0) {
        content.foreach(deleteRecursively)
      }
    }
    f.delete()
  }

  def removeIvyLocal() = {
    val org = CommonRaceSettings.org
    val userHome = System.getProperty("user.home")
    val ivyDir = new File(s"$userHome/.ivy2/local/$org")

    if (ivyDir.isDirectory) {
      deleteRecursively(ivyDir)
    }
  }
}
