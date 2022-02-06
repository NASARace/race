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
  * a generic type that represents a file that can be cached and provides a content getter that only
  * retrieves the data from the filesystem if the cached content is outdated
  *
  * TODO - we need a bounded LRU container that clears caches of rarely used files once we exceed a cache size threshold
  */
trait CachedFile[T] {
  val file: File
  protected var lastRead = DateTime.UndefinedDateTime
  protected var content: T = getContent()

  protected def fileContent(): T

  def getContent(): T = {
    val lastMod = FileUtils.lastModification(file)
    if (lastMod > lastRead || !isCached) {
      lastRead = lastMod
      content = fileContent()
    }
    content
  }

  def setContent(newContent: T): Unit
  def clearCache(): Unit
  def isCached: Boolean

  def lastModified: DateTime = lastRead
  def fileName: String = file.getName
  def fileExtension: String = FileUtils.getExtension(file)
}

class CachedByteFile (val file: File, fallback: =>Array[Byte]) extends CachedFile[Array[Byte]] {
  def this(f: File) = this(f, Array.empty[Byte])
  def this (pathName: String, fallback: =>Array[Byte]) = this(new File(pathName),fallback)

  override protected def fileContent(): Array[Byte] = FileUtils.fileContentsAsBytes(file).getOrElse(fallback)
  override def setContent(bs: Array[Byte]): Unit = FileUtils.setFileContents(file,bs)
  override def clearCache(): Unit = content = null
  override def isCached: Boolean = content != null
}

class CachedStringFile (val file: File, fallback: =>String) extends CachedFile[String] {
  def this (f: File) = this(f, "")
  def this (pathName: String, fallback: =>String) = this(new File(pathName),fallback)

  override protected def fileContent(): String = FileUtils.fileContentsAsString(file).getOrElse(fallback)
  override def setContent(newContent: String): Unit = FileUtils.setFileContents(file,newContent)
  override def clearCache(): Unit = content = null
  override def isCached: Boolean = content != null
}
