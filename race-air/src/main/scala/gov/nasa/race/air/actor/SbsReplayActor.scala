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
import gov.nasa.race.actor.Replayer
import gov.nasa.race.air.SbsUpdater
import gov.nasa.race.archive.ArchiveReader
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.PeriodicRaceActor
import gov.nasa.race.track.{TrackDropped, TrackedObject}
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{DateTime, Time}

import scala.concurrent.duration._

/**
  * a ArchiveReader for SBS text archives that minimizes buffer-copies and temporary allocations
  *
  * this class adds a per-track update pull interface to the SBSUpdater, which otherwise would read
  * all records in its current buffer when calling parse
  *
  * note that the primary ctor allows testing outside of an actor context
  */
class SBSReader (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int) extends ArchiveReader {

  def this(conf: Config) = this(createInputStream(conf), // this takes care of optional compression
                                configuredPathName(conf),
                                conf.getIntOrElse("buffer-size",4096)) // size has to hold at least 2 records

  class SbsArchiveUpdater extends SbsUpdater(updateTrack,dropTrack) {
    override protected def acquireMoreData: Boolean = refillBuf
  }

  val updater: SbsUpdater = new SbsArchiveUpdater
  var next: Option[ArchiveEntry] = None

  val buf = new Array[Byte](bufLen)  // the input buffer for reading iStream
  var limit = 0 // end of data in buf
  var recLimit = 0 // end of last record in [i0,limit] range

  //--- updater callbacks

  def updateTrack (track: TrackedObject): Boolean = {
    next = someEntry(track.date, track)
    false // process entries one at a time - stop the parse loop
  }

  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
    // override in derived class that supports drop checks
  }

  //--- the data acquisition loop

  def refillBuf: Boolean = {
    var isEnd = false

    @inline def recordLimit(bs: Array[Byte], len: Int): Int = {
      var i = len-1
      while (i>=0 && bs(i) != 10) i -= 1
      i+1
    }

    limit = if (recLimit < limit){
      // move leftover bytes to beginning and fill up
      val l = limit - recLimit
      System.arraycopy(buf,recLimit,buf,0,l)

      // note this relies on iStream not doing short reads if this is not the end
      // note also this does not necessarily hold for GZipInputStreams, depending on
      // inflater buffer size (not clear if this is an error)
      val max = buf.length - l
      val nRead = iStream.read(buf,l,max)
      isEnd = nRead < max
      l + nRead
    } else {
      iStream.read(buf,0,buf.length)
    }

    if (limit > 0) {
      if (!isEnd){ // no need to find recordLimit if this was the last data
        recLimit = recordLimit(buf,limit)
        if (recLimit <= 0) {
          throw new RuntimeException(s"buffer not long enough for SBS record (len=$limit)")
        }
      }
      updater.initialize(buf,recLimit)
      true
    } else false // end of data reached
  }

  //--- ArchiveReader interface

  override def hasMoreData: Boolean = {
    updater.hasMoreData || iStream.available > 0
  }

  override def readNextEntry: Option[ArchiveEntry] = {
    next = None
    updater.parse
    next
  }

  override def close: Unit = iStream.close

  def dropStale (date: DateTime, dropAfter: Time): Unit = updater.dropStale(date,dropAfter)

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
class SbsReplayActor(val config: Config) extends Replayer[SBSReader] with PeriodicRaceActor with SbsImporter {

  class DropCheckSBSReader (conf: Config) extends SBSReader(config) {
    override def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
      publish(TrackDropped(id,cs,date,Some(stationId)))
      info(s"dropping $id ($cs) at $date after $inactive")
    }
  }

  override def createReader = new DropCheckSBSReader(config) // note this is called during ReplayActor init so don't rely on SBSReplayActor ctor

  val dropAfter = Milliseconds(config.getFiniteDurationOrElse("drop-after", Duration.Zero).toMillis) // this is sim-time. Zero means don't check for drop
  override def startScheduler = if (dropAfter.nonZero) super.startScheduler  // only start scheduler if we have drop checks
  override def defaultTickInterval = 30.seconds  // wall clock time
  override def onRaceTick: Unit = reader.dropStale(updatedSimTime,dropAfter) // FIXME

}
