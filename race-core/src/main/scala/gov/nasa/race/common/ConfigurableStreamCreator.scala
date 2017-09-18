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

import gov.nasa.race.config.ConfigUtils._
import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStream}
import java.util.zip.GZIPOutputStream

import akka.actor.ActorRef
import com.typesafe.config.Config

/**
  * something that can write to a configurable file
  */
trait ConfigurableFileWriter {
  val config: Config

  def defaultPathName = "tmp/" +  config.getStringOrElse("name",getClass.getSimpleName) // override in concrete class
  val pathName = config.getStringOrElse("pathname", defaultPathName)
  val oStream: OutputStream = open // TODO - maybe the file should be opened on demand, not on construction

  def open: OutputStream = {
    val appendMode = config.getBooleanOrElse("append", false)
    val bufSize = config.getIntOrElse("buffer-size", 4096)
    val compressedMode = if (appendMode) false else config.getBooleanOrElse("compressed", pathName.endsWith(".gz"))

    val pn = if (compressedMode && !pathName.endsWith(".gz")) pathName + ".gz" else pathName
    val file = new File(pn)
    val dir = file.getParentFile
    if (!dir.isDirectory) dir.mkdirs()

    val fs = new FileOutputStream(file, appendMode)
    if (compressedMode) new GZIPOutputStream(fs,bufSize) else new BufferedOutputStream( fs, bufSize)
  }

  //--- forwards
  def close = {
    oStream.flush()
    oStream.close()
  }

  def flush: Unit = oStream.flush
  def write (b: Int): Unit = oStream.write(b)
  def write (ba: Array[Byte]): Unit = oStream.write(ba)
  def write (ba: Array[Byte], off: Int, len: Int): Unit = oStream.write(ba, off,len)

  @inline final def write (s: String): Unit = oStream.write(s.getBytes)
  @inline final def writeln = oStream.write('\n')
}
