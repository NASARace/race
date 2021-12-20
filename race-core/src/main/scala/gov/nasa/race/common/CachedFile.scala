/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import java.io.File

/**
  * cached file content with a fallback initializer that is only executed once
  */
class CachedFile(val file: File, fallback: =>Array[Byte]){

  def this (pathName: String, fallback: =>Array[Byte]) = this(new File(pathName),fallback)

  protected var lastRead = DateTime.UndefinedDateTime
  protected var content: Array[Byte] = getContentAsBytes()

  def getContentAsBytes(): Array[Byte] = {
    val lastMod = FileUtils.lastModification(file)
    if (lastMod > lastRead) {
      lastRead = lastMod
      content = FileUtils.fileContentsAsBytes(file).getOrElse(fallback)
    }
    content
  }
}
