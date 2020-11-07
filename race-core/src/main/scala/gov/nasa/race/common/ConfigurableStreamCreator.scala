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
package gov.nasa.race.common

import java.io._
import java.util.zip.GZIPOutputStream

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.GzInputStream

object ConfigurableStreamCreator {
  final val PathNameKey = "pathname"
  final val DefaultPathName = "<unknown>"
  final val BufSizeKey = "inflater-size"
  final val CompressedKey = "compressed"

  def createInputStream (conf: Config, defaultPathName:String = DefaultPathName): InputStream = {
    val pathName = conf.getStringOrElse(PathNameKey,defaultPathName)
    val compressedMode = conf.getBooleanOrElse(CompressedKey, pathName.endsWith(".gz"))

    val fs = new FileInputStream(pathName)
    if (compressedMode) {
      val bufSize = conf.getIntOrElse(BufSizeKey, 65536)
      new GzInputStream(fs,true,bufSize)
    } else {
      fs
    }
  }

  def createOutputStream (conf: Config, key: String, defaultPathName:String): OutputStream = {
    var pathName = conf.getStringOrElse(PathNameKey,defaultPathName)
    val appendMode = conf.getBooleanOrElse("append", false)
    val bufSize = conf.getIntOrElse(BufSizeKey, 4096)
    val compressedMode = if (appendMode) false else conf.getBooleanOrElse(CompressedKey, pathName.endsWith(".gz"))

    if (compressedMode && !pathName.endsWith(".gz")) pathName += ".gz"

    val file = new File(pathName)
    val dir = file.getParentFile
    if (!dir.isDirectory) dir.mkdirs()

    val fs = new FileOutputStream(file, appendMode)
    if (compressedMode) new GZIPOutputStream(fs,bufSize) else new BufferedOutputStream( fs, bufSize)
  }

  def createOutputStream (conf: Config, defaultPathName:String = DefaultPathName): OutputStream = {
    createOutputStream(conf, PathNameKey, defaultPathName)
  }

  // this is here to keep keys consistent
  def configuredPathName(conf: Config, defaultPathName:String = DefaultPathName) = conf.getStringOrElse(PathNameKey,defaultPathName)
}
