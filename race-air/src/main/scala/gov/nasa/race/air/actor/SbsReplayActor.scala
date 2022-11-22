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
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.LineBuffer
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.PeriodicRaceActor
import gov.nasa.race.track.{TrackDropped, Tracked3dObject}
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{DateTime, Time}

import java.time.ZoneId
import scala.concurrent.duration._

/**
  * a ArchiveReader for SBS text archives that minimizes buffer-copies and temporary allocations
  *
  * this class adds a per-track update pull interface to the SBSUpdater, which otherwise would read
  * all records in its current buffer when calling parse
  *
  * note that the primary ctor allows testing outside of an actor context
  */
class SBSReader (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int, defaultZone: ZoneId) extends ArchiveReader {

  def this(conf: Config) = this(createInputStream(conf), // this takes care of optional compression
                                configuredPathName(conf),
                                conf.getIntOrElse("buffer-size",4096),
                                conf.getMappedStringOrElse("default-zone", DateTime.getZoneId, ZoneId.systemDefault)
                               ) // size has to hold at least 2 records

  val updater: SbsUpdater = new SbsUpdater( Some(this), defaultZone)
  var next: Option[ArchiveEntry] = None

  val lineBuffer = new LineBuffer(iStream, 8192, bufLen)

  // override in derived class that supports drop checks
  def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {}

  //--- ArchiveReader interface

  override def hasMoreArchivedData: Boolean = {
    updater.hasMoreData || iStream.available > 0
  }

  override def readNextEntry(): Option[ArchiveEntry] = {
    next = None
    while (lineBuffer.nextLine() && next.isEmpty) {
      if (updater.initialize(lineBuffer)){
        updater.parse( track => {
          next = archiveEntry(track.date, track)
        })
      }
    }
    next
  }

  override def close(): Unit = iStream.close()

  def dropStale (date: DateTime, dropAfter: Time): Unit = updater.dropStale(date,dropAfter, dropTrack)

  //--- debugging

  def dumpContents: Unit = {
    var i = 0
    println("--- SBS archive contents:")
    while (hasMoreArchivedData) {
      readNextEntry() match {
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
class SbsReplayActor(val config: Config) extends Replayer with PeriodicRaceActor with SbsImporter {
  type R = SBSReader

  class DropCheckSBSReader (conf: Config) extends SBSReader(config) {
    override def dropTrack (id: String, cs: String, date: DateTime, inactive: Time): Unit = {
      publish(TrackDropped(id,cs,date,Some(stationId)))
      info(s"dropping $id ($cs) at $date after $inactive")
    }
  }

  override def createReader = new DropCheckSBSReader(config) // note this is called during ReplayActor init so don't rely on SBSReplayActor ctor

  val dropAfter = Milliseconds(config.getFiniteDurationOrElse("drop-after", Duration.Zero).toMillis.toInt) // this is sim-time. Zero means don't check for drop
  override def startScheduler = if (dropAfter.nonZero) super.startScheduler  // only start scheduler if we have drop checks
  override def defaultTickInterval = 30.seconds  // wall clock time
  override def onRaceTick(): Unit = reader.dropStale(updatedSimTime,dropAfter)

}
