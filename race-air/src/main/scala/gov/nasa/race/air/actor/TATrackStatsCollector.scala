/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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

import java.io.PrintWriter

import com.typesafe.config.Config
import gov.nasa.race.actor.StatsCollectorActor
import gov.nasa.race.air.TATrack
import gov.nasa.race.common.{ConfiguredTSStatsCollector, PrintStats, Stats, TSEntryData, TSStatsData}
import gov.nasa.race.core.ClockAdjuster
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}

import scala.collection.mutable.{HashMap => MHashMap}

/**
  * actor that collects statistics for TATrack objects
  * We keep stats per tracon, hence this is not directly a TSStatsCollectorActor
  */
class TATrackStatsCollector (val config: Config) extends StatsCollectorActor with ClockAdjuster {

  class TACollector (val config: Config, val src: String)
         extends ConfiguredTSStatsCollector[Int,TATrack,TATrackEntryData,TATrackStatsData] {
    val statsData = new TATrackStatsData(src)

    def createTSEntryData (t: Long, track: TATrack) = new TATrackEntryData(t,track)
    def currentSimTimeMillisSinceStart = TATrackStatsCollector.this.currentSimTimeMillisSinceStart
    def currentSimTimeMillis = TATrackStatsCollector.this.updatedSimTimeMillis
  }

  val tracons = MHashMap.empty[String, TACollector]

  override def handleMessage = {
    case BusEvent(_, track: TATrack, _) =>
      try {
        if (track.date != null) {
          checkClockReset(track.date)
          val tracon = tracons.getOrElseUpdate(track.src, new TACollector(config, track.src))
          if (track.isDrop) tracon.removeActive(track.trackNum) else tracon.updateActive(track.trackNum, track)
        }
      } catch {
        case t: Throwable => t.printStackTrace
      }

    case RaceTick =>
      tracons.foreach { e => e._2.checkDropped }
      publish(snapshot)
  }

  def snapshot: Stats = {
    val traconStats = tracons.toSeq.sortBy(_._1).map( e => e._2.dataSnapshot)
    new TATrackStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, traconStats)
  }
}

class TATrackEntryData (tLast: Long, track: TATrack) extends TSEntryData[TATrack](tLast,track) {
  var nFlightPlan = if (track.hasFlightPlan) 1 else 0 // messages with flight plan

  override def update (obj: TATrack, isSettled: Boolean) = {
    super.update(obj,isSettled)
    if (obj.hasFlightPlan) nFlightPlan += 1
  }
  // add consistency status
}

class TATrackStatsData  (val src: String) extends TSStatsData[TATrack,TATrackEntryData] {
  var nTimeless = 0 // number of track positions without time stamps
  var stddsV2 = 0
  var stddsV3 = 0

  def updateTATrackStats (obj: TATrack) = {
    obj.stddsRev match {
      case 2 => stddsV2 += 1
      case 3 => stddsV3 += 1
    }
    if (obj.date == null) nTimeless += 1
  }

  override def update (obj: TATrack, e: TATrackEntryData, isSettled: Boolean): Unit = {
    super.update(obj,e,isSettled) // standard TSStatsData collection
    updateTATrackStats(obj)
  }

  override def add (obj: TATrack, isStale: Boolean, isSettled: Boolean) = {
    super.add(obj,isStale,isSettled)
    updateTATrackStats(obj)
  }

  override def isDuplicate (t1: TATrack, t2: TATrack) = {
    t1.src == t2.src &&
      t1.trackNum == t2.trackNum &&
      t1.xyPos == t2.xyPos &&
      t1.altitude == t2.altitude &&
      t1.speed == t2.speed &&
      t1.heading == t2.heading &&
      t1.vVert == t2.vVert &&
      t1.beaconCode == t2.beaconCode // TODO - not sure about this one
  }
}

class TATrackStats(val topic: String, val source: String, val takeMillis: Long, val elapsedMillis: Long,
                   val traconStats: Seq[TATrackStatsData]) extends PrintStats {

  val nTracons = traconStats.size
  var nActive = 0
  var nDropped = 0
  var nOutOfOrder = 0
  var nAmbiguous = 0
  var stddsV2 = 0
  var stddsV3 = 0
  var nNoTime = 0
  var nNoPlan = 0

  traconStats.foreach { ts =>
    nActive += ts.nActive
    nDropped += ts.dropped
    nOutOfOrder += ts.outOfOrder
    nAmbiguous += ts.ambiguous
    if (ts.stddsV2 > 0) stddsV2 += 1
    if (ts.stddsV3 > 0) stddsV3 += 1
    nNoTime += ts.nTimeless
  }

    // the default is to print only stats for all centers
  override def printWith (pw: PrintWriter): Unit = {
    pw.println("  tracons  v2  v3  tracks   dropped   order   ambig   no-time   no-fp")
    pw.println("  ------- --- --- -------   ------- ------- -------   ------- -------")
    pw.print(f"  $nTracons%7d $stddsV2%3d $stddsV3%3d $nActive%7d")
    pw.print(f"   $nDropped%7d $nOutOfOrder%7d $nAmbiguous%7d   $nNoTime%7d $nNoPlan%7d")
  }
}