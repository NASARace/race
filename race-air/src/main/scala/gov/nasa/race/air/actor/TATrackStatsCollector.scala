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
import gov.nasa.race._
import gov.nasa.race.actor.StatsCollectorActor
import gov.nasa.race.air.TATrack
import gov.nasa.race.common.{ConfiguredTSStatsCollector, PrintStats, PrintStatsFormatter, Stats, TSEntryData, TSStatsData}
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
    statsData.buckets = createBuckets

    def createTSEntryData (t: Long, track: TATrack) = new TATrackEntryData(t,track)
    def currentSimTimeMillisSinceStart = TATrackStatsCollector.this.currentSimTimeMillisSinceStart
    def currentSimTimeMillis = TATrackStatsCollector.this.updatedSimTimeMillis

    override def dataSnapshot: TATrackStatsData = {
      processEntryData
      super.dataSnapshot
    }
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
    val traconStats = tracons.toSeq.sortBy(_._1).map( e=> e._2.dataSnapshot)
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
  var nNoTime = 0 // number of track positions without time stamps
  var nFlightPlans = 0 // number of active entries with flight plan
  var stddsV2 = 0
  var stddsV3 = 0

  def updateTATrackStats (obj: TATrack) = {
    obj.stddsRev match {
      case 2 => stddsV2 += 1
      case 3 => stddsV3 += 1
    }
    if (obj.date == null) nNoTime += 1
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
    t1.src == t2.src
      t1.status == t2.status &&
      t1.trackNum == t2.trackNum &&
      t1.xyPos == t2.xyPos &&
      t1.altitude == t2.altitude &&
      t1.speed == t2.speed &&
      t1.heading == t2.heading &&
      t1.vVert == t2.vVert &&
      t1.beaconCode == t2.beaconCode &&
      t1.attrs == t2.attrs
  }

  override def resetEntryData = {
    nFlightPlans = 0
  }

  // called on all active entries before the Stats snapshot is created
  override def processEntryData (e: TATrackEntryData) = {
    if (e.nFlightPlan > 0) nFlightPlans += 1
  }

  def stddsRev = {
    if (stddsV2 > 0){
      if (stddsV3 > 0) "2/3" else "2"
    } else {
      if (stddsV3 > 0) "3" else "?"
    }
  }
}

class TATrackStats(val topic: String, val source: String, val takeMillis: Long, val elapsedMillis: Long,
                   val traconStats: Seq[TATrackStatsData]) extends PrintStats {

  val nTracons = traconStats.size
  var nActive = 0
  var nCompleted = 0
  var nFlightPlans = 0
  var nDropped = 0
  var nOutOfOrder = 0
  var nDuplicates = 0
  var nAmbiguous = 0
  var stddsV2 = 0
  var stddsV3 = 0
  var nNoTime = 0

  traconStats.foreach { ts =>
    nActive += ts.nActive
    nFlightPlans += ts.nFlightPlans
    nCompleted += ts.completed

    nDropped += ts.dropped
    nOutOfOrder += ts.outOfOrder
    nDuplicates += ts.duplicate
    nAmbiguous += ts.ambiguous
    if (ts.stddsV2 > 0) stddsV2 += 1
    if (ts.stddsV3 > 0) stddsV3 += 1
    nNoTime += ts.nNoTime
  }

  // the default is to print only stats for all centers
  override def printWith (pw: PrintWriter): Unit = {
    pw.println("tracons  v2  v3    tracks   fplan   cmplt   dropped   order     dup   ambig   no-time")
    pw.println("------- --- ---   ------- ------- -------   ------- ------- ------- -------   -------")
    pw.print(f"$nTracons%7d $stddsV2%3d $stddsV3%3d   $nActive%7d $nFlightPlans%7d $nCompleted%7d")
    pw.print(f"   $nDropped%7d $nOutOfOrder%7d $nDuplicates%7d $nAmbiguous%7d   $nNoTime%7d")
  }
}

class TATrackStatsFormatter (conf: Config) extends PrintStatsFormatter {

  def printWith (pw: PrintWriter, stats: Stats) = {
    stats match {
      case s: TATrackStats => printTATrackStats(pw,s)
      case other => false
    }
  }

  def printTATrackStats (pw: PrintWriter, s: TATrackStats): Boolean = {
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}
    import s._

    //--- totals
    s.printWith(pw)
    pw.print("\n\n")

    //--- per tracon data
    pw.println(" tracon     rev    tracks   fplan   cmplt   dropped   order     dup   ambig   no-time         n dtMin dtMax dtAvg")
    pw.println("------- -------   ------- ------- -------   ------- ------- ------- -------   -------   ------- ----- ----- -----")
    traconStats.foreach { ts =>
      pw.print(f"${ts.src}%7s ${ts.stddsRev}%7s   ${ts.nActive}%7d ${ts.nFlightPlans}%7d ${ts.completed}%7d   ")
      pw.print(f"${ts.dropped}%7d ${ts.outOfOrder}%7d ${ts.duplicate}%7d ${ts.ambiguous}%7d   ${ts.nNoTime}%7d")
      ifSome(ts.buckets) { bc =>
        pw.print(f"   ${bc.nSamples}%7d ${dur(bc.min)}%5s ${dur(bc.max)}%5s ${dur(bc.mean)}%5s")
      }
      pw.println
    }

    true
  }
}