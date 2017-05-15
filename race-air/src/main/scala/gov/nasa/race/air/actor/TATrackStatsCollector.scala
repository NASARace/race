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
import gov.nasa.race.common.{ConsoleStats, Stats, TimeSeriesStats}
import gov.nasa.race.core.ClockAdjuster
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}

import scala.collection.mutable.{HashMap => MHashMap}

/**
  * actor that collects statistics for TATrack objects
  *
class TATrackStatsCollector (val config: Config) extends StatsCollectorActor
                                  with ClockAdjuster with TimeSeriesUpdateContext[TATrack] {

  val tracons = MHashMap.empty[String,ConfigurableTimeSeriesStats[Int,TATrack]]

  override def handleMessage = {
    case BusEvent(_, track: TATrack, _) =>
      try {
        if (track.date != null) {
          checkClockReset(track.date)
          val tracon = tracons.getOrElseUpdate(track.src, new ConfigurableTimeSeriesStats[Int, TATrack](config, this))
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
    val traconStats = tracons.toSeq.sortBy(_._1).map( e=> new TraconStats(e._1,e._2.snapshot))
    new TATrackStats(title, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, channels, traconStats)
  }

  // TimeSeriesUpdateContext interface (note that if this returns false, the objects are considered to be ambiguous
  override def isDuplicate(t1: TATrack, t2: TATrack): Boolean = {
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

class TraconStats (val src: String, val updateStats: TimeSeriesStats)

class TATrackStats(val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                   val traconStats: Seq[TraconStats]) extends Stats with ConsoleStats {

  override def writeToConsole(pw: PrintWriter): Unit = {
    pw.println(consoleHeader)
    pw.println(s"observed channels: $channels")

    pw.println("src    active    min    max   cmplt stale  drop order   dup ambig        n dtMin dtMax dtAvg")
    pw.println("------ ------ ------ ------   ----- ----- ----- ----- ----- -----  ------- ----- ----- -----")

    traconStats.foreach { ts =>
      pw.print(f"${ts.src}%6.6s ")
      val ups = ts.updateStats
      import ups._
      pw.println(f"$nActive%6d $minActive%6d $maxActive%6d   $completed%5d $stale%5d $dropped%5d $outOfOrder%5d $duplicate%5d $ambiguous%5d ")
    }
  }
}

  */