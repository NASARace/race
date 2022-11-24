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
package gov.nasa.race.earth.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.actor.Replayer
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
import gov.nasa.race.earth.{ViirsHotspot, ViirsHotspotArchiveReader, ViirsHotspots}
import gov.nasa.race.space.{OreKit, OreKitActor, OverpassRegion, OverpassSeq, TLE, TleArchiveOwner}
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.uom.Time.{DayDuration, Days, Minutes}

import scala.collection.mutable.{Queue => MutQueue}
import scala.collection.mutable.ArrayBuffer

/**
 * this is a decorating ArchiveReader that uses the underlying ViirsHotspotArchiveReader to collect entries that
 * fall within the next overpass, which is set per readNextEntry() call by our replay actor
 */
class JpssOverpassReader (viirsReader: ViirsHotspotArchiveReader, margin: Time) extends ArchiveReader {

  protected var nextOverpass: Option[OverpassSeq] = None // to be set by owner (contains satId)

  protected var leftOver: Option[ViirsHotspots] = None
  protected val nextHs = ArrayBuffer.empty[ViirsHotspot]

  override def readNextEntry(): Option[ArchiveEntry] = {
    def emptyEntry (satId: Int, date: DateTime): Option[ArchiveEntry] = archiveEntry( date, ViirsHotspots(date, satId, "<unknown>", Seq.empty))

    nextOverpass.foreach { ops =>
      val lowCutoff = ops.firstDate - margin // give it some leeway as we get pixel time outside overpasses from archives
      val highCutoff = ops.lastDate + margin

      leftOver match {
        case Some(hs) =>
          if (hs.date <= ops.date) nextHs ++= hs.data // accumulate and go on
          else return emptyEntry( ops.satId, ops.date) // no data for nextOverpass
        case None => // no leftover, go on
      }

      while (true){ // collect all hotspots from the underlying archive that fall within nextOverpass period
        viirsReader.readNextEntry() match {
          case Some(e) =>
            e.msg match {
              case vhs: ViirsHotspots =>
                if (vhs.date >= lowCutoff) {
                  if (vhs.date <= highCutoff) { // accumulate
                    nextHs ++= vhs.data
                  } else { // outside nextOverpass -
                    leftOver = Some(vhs)
                    if (nextHs.nonEmpty) {
                      val ohs = ViirsHotspots(ops.lastDate, nextHs.head.satId, nextHs.head.source, nextHs.toSeq)
                      nextHs.clear() // free early
                      return archiveEntry( ops.date, ohs)
                    } else return emptyEntry( ops.satId, ops.date) // no data for this overpass
                  }
                } else {
                  // before lowCutoff -> ignore and loop on
                }
              case _ => // we ignore other viirs archive entries
            }
          case None => return None // no more data in underlying archive
        }
      }
    }
    None // no overpass set
  }

  //--- delegations
  override def hasMoreArchivedData: Boolean = viirsReader.hasMoreArchivedData
  override def close(): Unit = viirsReader.close()
  override val pathName: String = viirsReader.pathName

  // called by ReplayActor before readNextEntry()
  def setNextOverpass (op: Option[OverpassSeq]): Unit = nextOverpass = op
}

/**
 * Hotspot replay actor that emits ViirsHotspots messages at historical overpass times computed from
 * configured TLE archives. Note there is one JpssReplayActor per satellite
 */
class JpssReplayActor (val config: Config) extends JpssActor with Replayer with TleArchiveOwner with OreKitActor {
  type R = JpssOverpassReader

  val pastOverpasses: MutQueue[OverpassSeq] = MutQueue.empty  // TODO - do we need this?

  overpasses ++= getHistoricalOverpasses() // initialize with history, we still get data for them

  override def createReader = new JpssOverpassReader( new ViirsHotspotArchiveReader(config), overpassMargin)

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator) && {
      getOverpassRegions.foreach(publish)
      pastOverpasses.foreach(publish)
      overpasses.foreach(publish)
      true
    }
  }

  override protected def readNextEntry(): Option[ArchiveEntry] = {
    if (overpasses.isEmpty) { // get next sequence of overpasses (if any)
      val nextOverpasses = getUpcomingOverpasses(Days(1))
      nextOverpasses.foreach(publish)
      overpasses ++= nextOverpasses
    }

    if (overpasses.nonEmpty) {
      reader.setNextOverpass(overpasses.headOption)
      reader.readNextEntry()
    } else None
  }

  override protected def replay (msg: Any): Unit = {
    msg match {
      case vhs: ViirsHotspots =>
        if (vhs.nonEmpty) {
          vhs.data.foreach( setHotspotBounds)
          publish(vhs)
        }
        pastOverpasses += overpasses.dequeue()  // FIXME - this should only remove the overpasses covered by vhs

      case other => publish(other)
    }
  }

  override protected def skipEntry (e: ArchiveEntry): Boolean = { e.date < baseSimTime - history }

  def setHotspotBounds(e: ArchiveEntry): Unit = {
    e.msg match {
      case vhs: ViirsHotspots => vhs.data.foreach( setHotspotBounds)
      case _ => // nothing
    }
  }

  def getHistoricalOverpasses(): Array[OverpassSeq] = {
    val list = ArrayBuffer.empty[OverpassSeq]

    if (!initOreKit()){
      warning(s"failed to initialize OreKit - no overpass data")

    } else {
      val startDate = currentSimTime - Days(history.toDays)
      val endDate = currentSimTime
      var d = startDate
      while (d < endDate) {
        foreachClosestTLE(d) { tle=> // we might have more than one satellite
          list ++= getOverpasses( tle, d, DayDuration)
        }
        d = d + DayDuration
      }
    }

    var opss = list.toArray
    if (numberOfTLESatellites > 1) opss = opss.sortWith( (a,b) => a.lastDate < b.lastDate)  // in case we have several satellites
    opss
  }

  def getUpcomingOverpasses(dur: Time): Array[OverpassSeq] = {
    val startDate = currentSimTime
    findClosestTLE( startDate) match {
      case Some(tle) => getOverpasses( tle, startDate, dur)
      case None => warning(s"no suitable TLE for satellite $satId on $startDate"); Array.empty
    }
  }
}
