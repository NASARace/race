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
package gov.nasa.race.air.actor

import java.io.InputStream

import com.typesafe.config.Config
import gov.nasa.race.actor.ReplayActor
import gov.nasa.race.air.SBSUpdater
import gov.nasa.race.archive.ArchiveReader
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom.{DateTime, Time}

/**
  * a ArchiveReader for SBS text archives that minimizes buffer-copies and temporary allocations
  *
  * note that the primary ctor allows testing outside of an actor context
  */
class SBSReader (val iStream: InputStream,
                 val pathName: String="<unknown>", bufLen: Int) extends ArchiveReader {

  def this(conf: Config) = this(createInputStream(conf), // this takes care of optional compression
                                configuredPathName(conf),
                                conf.getIntOrElse("buffer-size",4096)) // size has to hold at least 2 records

  val updater: SBSUpdater = new SBSUpdater(updateTrack,dropTrack)
  var next: Option[ArchiveEntry] = None

  val buf = new Array[Byte](bufLen)  // the input buffer for reading iStream
  var limit = 0 // end of data in buf
  var recLimit = 0 // end of last record in [i0,limit] range

  //--- updater callbacks
  def updateTrack (track: TrackedObject): Boolean = {
    next = someEntry(track.date, track)
    false // process entries one at a time
  }

  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {

  }

  //--- the data acquisition loop

  def refillBuf: Boolean = {
    @inline def recordLimit(bs: Array[Byte], len: Int): Int = {
      var i = len-1
      while (i>=0 && bs(i) != 10) i -= 1
      i+1
    }

    limit = if (recLimit < limit){
      // move leftover bytes to beginning and fill up
      val l = limit - recLimit
      System.arraycopy(buf,recLimit,buf,0,l)
      l + iStream.read(buf,l,buf.length - l)
    } else {
      iStream.read(buf,0,buf.length)
    }

    if (limit > 0) {
      recLimit = recordLimit(buf,limit)
      if (recLimit > 0) {
        updater.initialize(buf,recLimit)
      } else throw new RuntimeException(s"buffer not long enough for SBS record (len=$limit)")
    } else false // end of data reached
  }

  //--- ArchiveReader interface
  override def hasMoreData: Boolean = updater.hasMoreData || iStream.available > 0

  override def readNextEntry: Option[ArchiveEntry] = {
    if (!updater.hasMoreData) {  // refill buffer
      if (!refillBuf) return None
    }

    next = None
    updater.parse
    next
  }

  override def close: Unit = iStream.close

  //--- debugging
  def dumpContents: Unit = {
    var i = 0
    println("--- SBS archive contents:")
    while (hasMoreData) {
      readNextEntry match {
        case Some(e) =>
          i += 1
          println(s"$i: ${e.date} -> ${e.msg}")
        case None => // println("none.")
      }
    }
    println("--- done.")
  }
}

/**
  * specialized ReplayActor for SBS text archives
  */
class SBSReplayActor (override val config: Config) extends ReplayActor(config) {

  override def createReader = new SBSReader(config) // we reuse our own config to keep it symmetric to SBSArchiveActor

}
