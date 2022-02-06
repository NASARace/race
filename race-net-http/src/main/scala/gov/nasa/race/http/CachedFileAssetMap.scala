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
package gov.nasa.race.http

import gov.nasa.race.common.CachedByteFile
import gov.nasa.race.util.ClassUtils

object CachedFileAssetMap {
  def fromClass(cls: Class[_], fileName: String): Array[Byte] = {
    ClassUtils.getResourceAsBytes(cls, fileName).getOrElse(Array.empty)
  }
}

/**
  * something that maintains a map of paths holding maps with assetName->CachedFile pairs
  * this is used for semi-static assets (i.e. occasionally changed files), /not/ for dynamically computed assets
  *
  * each asset request has to provide a fallback function, which usually retrieves the asset content from
  * class resources (i.e. the jar that contains the implementor code).
  *
  * Note this fallback is executed at most once, if the asset file is not found in the provided path
  */
trait CachedFileAssetMap {
  protected var assetPathMap: Map[String, Map[String,CachedByteFile]] = Map.empty

  def getContent (path: String, fileName: String, fallBack: =>Array[Byte]):  Array[Byte] = {
    assetPathMap.get(path) match {
      case Some(assetMap) => // path already known
        assetMap.get(fileName) match {
          case Some (cf) =>
            cf.getContent()
          case None => // no asset for this filename yet
            val cf = new CachedByteFile( path + '/' + fileName, fallBack)
            assetPathMap = assetPathMap + (path -> (assetMap + (fileName -> cf)))
            cf.getContent()
        }
      case None => // no assets for this path yet
        val cf = new CachedByteFile( path + '/' + fileName, fallBack)
        assetPathMap = assetPathMap + (path -> Map((fileName -> cf)))
        cf.getContent()
    }
  }

  def sourcePath: String

  def getContent(fileName: String): Array[Byte] = {
    getContent(sourcePath,fileName,CachedFileAssetMap.fromClass(getClass(), fileName))
  }
}

class CachedFileMap (val sourcePath: String) extends CachedFileAssetMap