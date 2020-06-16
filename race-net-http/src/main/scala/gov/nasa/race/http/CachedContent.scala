/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.http

import java.io.File

import akka.http.scaladsl.model.HttpEntity
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

/**
  * aggregate container for cached content
  * this has to contain all elements we might need for completing a HttpRequest (entity plus respective headers)
  */
case class CachedContent (siteRoot: File, requestPrefix: String, relPath: String, file: File, isTokenIncrement: Boolean, entity:HttpEntity.Strict) {
  val lastMod: DateTime = FileUtils.lastModification(file)
  val location: Option[String] = getLocation // in case siteDir + relPath is not the file path

  def isOutdated: Boolean = FileUtils.lastModification(file) > lastMod

  def getLocation: Option[String] = {
    val loc = file.getPath.substring(siteRoot.getPath.length)
    if (loc != relPath) Some(s"/$requestPrefix$loc") else None
  }
}
