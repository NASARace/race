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

package gov.nasa.race.actors.exports

import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStream}
import java.util.zip.GZIPOutputStream

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core._
import gov.nasa.race.data.ArchiveWriter

/**
 * actor that writes the subscribed channel to disk
 */
class ArchiveActor (val config: Config) extends SubscribingRaceActor with ContinuousTimeRaceActor {
  val pathName = config.getString("pathname")
  val writerCls = config.getString("archive-writer")
  var compressedMode = config.getBooleanOrElse("compressed", false)
  val appendMode = config.getBooleanOrElse("append", false)
  val bufSize = config.getIntOrElse("buffer-size", 4096)

  var stopArchiving = false
  val oStream = openStream
  val archiveWriter = newInstance[ArchiveWriter](writerCls, Array(classOf[OutputStream]), Array(oStream)).get

  if (compressedMode && appendMode) {
    warning(s"$name cannot append to compressed stream, disabling compression")
    compressedMode = false
  }
  log.info(s"$name archiving channels [$readFromAsString] to $pathName (compress=$compressedMode,append=$appendMode)")

  def openStream: OutputStream = {
    val pn = if (compressedMode) pathName + ".gz" else pathName
    val file = new File(pn)
    val dir = file.getParentFile
    if (!dir.isDirectory) dir.mkdirs()

    val fs = new FileOutputStream(file, appendMode)
    if (compressedMode) new GZIPOutputStream(fs,bufSize) else new BufferedOutputStream( fs, bufSize)
  }

  override def terminateRaceActor (originator: ActorRef) = {
    super.terminateRaceActor(originator)
    stopArchiving = true
    archiveWriter.close
    oStream.close
  }

  override def handleMessage: Receive = {
    case BusEvent(_,msg,_) =>
      if (!stopArchiving) {
        updateSimTime
        if (archiveWriter.write(simTime, msg)) {
          debug(s"${name} archived $msg")
        } else {
          debug(s"${name} ignored $msg")
        }
      }
  }
}
